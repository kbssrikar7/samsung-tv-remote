package com.kbs.tvremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.kbs.tvremote.ui.NowPlaying
import com.kbs.tvremote.ui.NowPlayingActions
import com.kbs.tvremote.ui.RemoteActions
import com.kbs.tvremote.ui.RemoteScreen
import com.kbs.tvremote.ui.RemoteState
import com.kbs.tvremote.ui.RemoteTheme
import com.kbs.tvremote.ui.Status
import com.kbs.tvremote.ui.TvApp
import com.kbs.tvremote.ui.YouTubeActions
import com.kbs.tvremote.ui.YouTubeState
import com.kbs.tvremote.youtube.Library
import com.kbs.tvremote.youtube.LoungeClient
import com.kbs.tvremote.youtube.Video
import com.kbs.tvremote.youtube.YouTubeSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TvClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { TvPrefs.of(this) }
    private val client by lazy { TvClient(this, prefs) }

    // Internet traffic (YouTube) uses normal certificate validation, unlike the TV connection.
    private val web by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val youtubeSearch by lazy { YouTubeSearch(web) }
    private val lounge by lazy { LoungeClient(web, prefs) }
    private val library by lazy { Library(prefs) }
    private var watchJob: Job? = null
    /** Video sent after a cold start, completed once the TV reports playing it. */
    private var awaitedVideo: Pair<String, CompletableDeferred<Unit>>? = null
    private val volumeRefresh = Runnable {
        lifecycleScope.launch {
            val volume = withContext(Dispatchers.IO) { client.volume() } ?: return@launch
            uiState = uiState.copy(volume = volume)
        }
    }

    private var resumed = false
    /** Grows while the TV stays unreachable so an off TV doesn't keep the radio busy. */
    private var reconnectDelayMs = RECONNECT_DELAY_MS

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RemoteNotification.show(this) else RemoteNotification.setEnabled(this, false)
        uiState = uiState.copy(notificationEnabled = RemoteNotification.isEnabled(this))
    }
    private val reconnect = Runnable { connect() }
    private var discovery: Job? = null

    /** The chosen TV's address, or null until one has been found or entered. */
    private val ip: String? get() = prefs.getString(PREF_IP, null)

    private var uiState by mutableStateOf(
        RemoteState(tvName = "Samsung TV", status = Status.CONNECTING, ip = null, youtube = YouTubeState(linked = false))
    )

    private fun updateYouTube(change: YouTubeState.() -> YouTubeState) {
        uiState = uiState.copy(youtube = uiState.youtube.change())
    }

    private val youtubeActions = YouTubeActions(
        search = { query ->
            updateYouTube { copy(searching = true, searchedFor = query, error = null, recentSearches = library.addRecentSearch(query)) }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { youtubeSearch.search(query) } }
                updateYouTube {
                    copy(
                        searching = false,
                        results = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.let { "Couldn't search. Check the phone's internet." },
                    )
                }
            }
        },
        clear = { updateYouTube { copy(searchedFor = null, results = emptyList(), error = null) } },
        play = { video -> playOnTv(video) },
        playNext = { video -> queue(video, "Will play next") { lounge.playNext(it) } },
        addToQueue = { video -> queue(video, "Added to the queue") { lounge.addToQueue(it) } },
        toggleFavourite = { video -> updateYouTube { copy(favourites = library.toggleFavourite(video)) } },
        link = { code ->
            updateYouTube { copy(linking = true, error = null) }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { lounge.pair(code) } }
                updateYouTube {
                    copy(
                        linking = false,
                        linked = lounge.isLinked,
                        error = result.exceptionOrNull()?.let { it.message ?: "Couldn't connect to YouTube" },
                    )
                }
                if (lounge.isLinked) startWatching()
            }
        },
        unlink = {
            stopWatching()
            lounge.unlink()
            updateYouTube { YouTubeState(linked = false, favourites = favourites, recentSearches = recentSearches) }
            uiState = uiState.copy(nowPlaying = null)
        },
    )

    private val actions = RemoteActions(
        key = { key -> sendKey(key) },
        launch = { app -> if (app == TvApp.YOUTUBE) openYouTube() else client.launchApp(APPS.getValue(app)) },
        power = { power() },
        retry = {
            client.disconnect()
            if (ip == null) findTv() else connect()
        },
        youtube = youtubeActions,
        findTv = { findTv() },
        setNotification = { enabled -> setNotification(enabled) },
        nowPlaying = NowPlayingActions(
            togglePlay = { togglePlay() },
            seekTo = { seconds -> inBackground { lounge.seekTo(seconds) } },
        ),
        chooseTv = { tv -> useTv(tv.ip, tv.name) },
        saveIp = { newIp -> useTv(newIp, null) },
        forgetPairing = {
            ip?.let { current -> prefs.edit { remove(tokenKey(current)); remove(TvClient.pinKey(current)) } }
            client.disconnect()
            connect()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uiState = uiState.copy(
            tvName = ip?.let { prefs.getString(nameKey(it), null) } ?: uiState.tvName,
            ip = ip,
            youtube = YouTubeState(
                linked = lounge.isLinked,
                favourites = library.favourites(),
                recentSearches = library.recentSearches(),
            ),
        )
        uiState = uiState.copy(notificationEnabled = RemoteNotification.isEnabled(this))
        if (RemoteNotification.isEnabled(this)) {
            if (RemoteNotification.hasPermission(this)) RemoteNotification.show(this) else askNotificationPermissionOnce()
        }
        setContent {
            RemoteTheme { RemoteScreen(uiState, actions) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reconnectDelayMs = RECONNECT_DELAY_MS
        if (ip == null) findTv() else connect()
        if (lounge.isLinked) startWatching()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(reconnect)
        client.disconnect()
        stopWatching()
    }

    private fun setNotification(enabled: Boolean) {
        RemoteNotification.setEnabled(this, enabled)
        // hasPermission is always true below Android 13, so this only asks where it is needed.
        if (enabled && !RemoteNotification.hasPermission(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        uiState = uiState.copy(notificationEnabled = RemoteNotification.isEnabled(this))
    }

    /** Android 13+ needs permission for the notification remote; ask on first launch only. */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun sendKey(key: String) {
        if (client.state == TvClient.State.DISCONNECTED) connect()
        client.sendKey(key)
        if (key in VOLUME_KEYS) refreshVolume()
    }

    /** Reads the TV's volume shortly after the last volume key, so repeated presses coalesce. */
    private fun refreshVolume(delayMs: Long = 400) {
        main.removeCallbacks(volumeRefresh)
        main.postDelayed(volumeRefresh, delayMs)
    }

    /** Follows what the TV's YouTube is playing while the app is in front. */
    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = lifecycleScope.launch(Dispatchers.IO) {
            lounge.watch { playback -> main.post { onPlayback(playback) } }
        }
    }

    private fun stopWatching() {
        lounge.stopWatching()
        watchJob?.cancel()
        watchJob = null
    }

    private fun onPlayback(playback: LoungeClient.Playback?) {
        awaitedVideo?.let { (id, confirmed) -> if (playback?.videoId == id) confirmed.complete(Unit) }
        if (playback == null || playback.state == LoungeClient.State.STOPPED) {
            uiState = uiState.copy(nowPlaying = null)
            return
        }
        val previous = uiState.nowPlaying
        val known = previous?.video?.takeIf { it.id == playback.videoId }
            ?: (uiState.youtube.results + uiState.youtube.favourites).firstOrNull { it.id == playback.videoId }
        uiState = uiState.copy(nowPlaying = NowPlaying(known, playback, System.currentTimeMillis()))
        if (known == null) {
            lifecycleScope.launch {
                val details = withContext(Dispatchers.IO) { youtubeSearch.details(playback.videoId) } ?: return@launch
                val current = uiState.nowPlaying ?: return@launch
                if (current.playback.videoId == details.id) uiState = uiState.copy(nowPlaying = current.copy(video = details))
            }
        }
    }

    private fun togglePlay() {
        val current = uiState.nowPlaying ?: return
        val playing = current.playback.state == LoungeClient.State.PLAYING
        // Show the change at once; the TV's own report follows and corrects it if needed.
        val state = if (playing) LoungeClient.State.PAUSED else LoungeClient.State.PLAYING
        uiState = uiState.copy(nowPlaying = current.copy(playback = current.playback.copy(state = state)))
        inBackground { if (playing) lounge.pause() else lounge.play() }
    }

    private fun queue(video: Video, done: String, send: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { send(video.id) } }
            toast(if (result.isSuccess) done else result.exceptionOrNull()?.message ?: "Couldn't reach YouTube on the TV")
        }
    }

    private fun inBackground(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { e ->
                withContext(Dispatchers.Main) { toast(e.message ?: "Couldn't reach YouTube on the TV") }
            }
        }
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        val ip = ip ?: return
        uiState = uiState.copy(ip = ip)
        client.connect(ip, prefs.getString(tokenKey(ip), null))
        client.fetchInfo(ip) { name, mac ->
            prefs.edit {
                if (name.isNotEmpty()) putString(nameKey(ip), name)
                if (mac.isNotEmpty()) putString(macKey(ip), mac)
            }
            if (name.isNotEmpty()) uiState = uiState.copy(tvName = name)
        }
    }

    /** Searches the Wi-Fi for Samsung TVs; picks the only one found or lets the user choose. */
    private fun findTv() {
        if (discovery?.isActive == true) return
        main.removeCallbacks(reconnect)
        uiState = uiState.copy(status = Status.SEARCHING, foundTvs = emptyList())
        discovery = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { TvDiscovery.discover() }.getOrDefault(emptyList()) }
            when (found.size) {
                0 -> uiState = uiState.copy(status = Status.NOT_FOUND)
                1 -> useTv(found[0].ip, found[0].name)
                else -> uiState = uiState.copy(status = Status.NOT_FOUND, foundTvs = found)
            }
        }
    }

    private fun useTv(newIp: String, name: String?) {
        prefs.edit {
            putString(PREF_IP, newIp)
            if (name != null) putString(nameKey(newIp), name)
        }
        uiState = uiState.copy(foundTvs = emptyList(), tvName = name ?: uiState.tvName)
        client.disconnect()
        connect()
    }

    /** Phone volume buttons control the TV while the app is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "KEY_VOLUP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "KEY_VOLDOWN"
            else -> return super.onKeyDown(keyCode, event)
        }
        if (client.state != TvClient.State.CONNECTED) return super.onKeyDown(keyCode, event)
        sendKey(key)
        return true
    }

    /**
     * Blocking. Makes sure TizenTube is in front on the TV and returns true if it had to be started.
     * A freshly started TizenBrew stops on its module list unless its autolaunch is set, so the app
     * presses Down and OK to enter TizenTube. Keys are only ever sent right after the app started
     * TizenBrew itself, so they can't disturb something playing.
     */
    private fun ensureYouTubeOpen(): Boolean {
        val app = APPS.getValue(TvApp.YOUTUBE)
        val before = client.appStatus(app) ?: throw IOException("Can't reach the TV. Is it on?")
        if (before.running) {
            if (!before.visible) client.launchApp(app) // resumes straight into TizenTube
            return false
        }
        client.launchApp(app)
        waitUntilInFront(app)
        // TizenBrew starts with its top bar highlighted, so OK alone does nothing: move down onto
        // TizenTube (the first module) and open it.
        pressKey("KEY_DOWN")
        Thread.sleep(800)
        pressKey("KEY_ENTER")
        return true
    }

    /**
     * Blocking. Sends [key] on the app's connection, or on a one-off connection if the app's has
     * closed (it does when the app leaves the screen during a long wait).
     */
    private fun pressKey(key: String) {
        if (!client.sendKey(key)) KeyReceiver.sendKeyOnce(applicationContext, key)
    }

    /** Blocking. TizenBrew restarts itself once while starting; waits until it has stayed in front. */
    private fun waitUntilInFront(app: TvClient.TvApp) {
        val deadline = System.currentTimeMillis() + 20_000
        var steady = 0
        while (steady < 2) {
            if (System.currentTimeMillis() > deadline) throw IOException("YouTube didn't open on the TV")
            Thread.sleep(1000)
            steady = if (client.appStatus(app)?.visible == true) steady + 1 else 0
        }
        Thread.sleep(1500) // let the module list render
    }

    private fun openYouTube() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ensureYouTubeOpen() }.onFailure { e ->
                withContext(Dispatchers.Main) { toast(e.message ?: "Couldn't open YouTube") }
            }
        }
    }

    /** Plays [video] in the TV's YouTube (TizenTube), opening it first when needed. */
    private fun playOnTv(video: Video) {
        updateYouTube { copy(sendingId = video.id, error = null) }
        startWatching()
        lifecycleScope.launch {
            val result = runCatching {
                val coldStart = withContext(Dispatchers.IO) { ensureYouTubeOpen() }
                if (!coldStart) {
                    withContext(Dispatchers.IO) { lounge.playVideo(video.id) }
                    return@runCatching
                }
                // A just-started TizenTube may not be listening yet, and a video sent too early is
                // silently dropped. Resend until the TV reports it playing.
                delay(TIZENTUBE_LOAD_MS)
                val confirmed = CompletableDeferred<Unit>()
                awaitedVideo = video.id to confirmed
                try {
                    val deadline = System.currentTimeMillis() + PLAY_CONFIRM_TIMEOUT_MS
                    while (true) {
                        withContext(Dispatchers.IO) { lounge.playVideo(video.id) }
                        if (withTimeoutOrNull(PLAY_RESEND_MS) { confirmed.await() } != null) break
                        if (System.currentTimeMillis() > deadline) throw IOException("YouTube on the TV didn't start the video")
                    }
                } finally {
                    awaitedVideo = null
                }
            }
            updateYouTube {
                copy(
                    sendingId = null,
                    playingId = if (result.isSuccess) video.id else playingId,
                    error = result.exceptionOrNull()?.let {
                        if (it is IOException) it.message else "Couldn't play on the TV"
                    },
                )
            }
        }
    }

    private fun power() {
        if (client.state == TvClient.State.CONNECTED) {
            client.sendKey("KEY_POWER")
            return
        }
        val mac = ip?.let { prefs.getString(macKey(it), null) }
        if (mac == null) {
            toast("Can't reach the TV. Turn it on with the normal remote once.")
            return
        }
        client.wake(mac)
        toast("Turning the TV on…")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, 4000)
    }

    override fun onStateChanged(state: TvClient.State, detail: String?) {
        val denied = detail == TvClient.DENIED
        val status = when (state) {
            TvClient.State.CONNECTED -> Status.READY
            TvClient.State.CONNECTING -> Status.CONNECTING
            TvClient.State.WAITING_FOR_ALLOW -> Status.ALLOW
            TvClient.State.DISCONNECTED -> if (denied) Status.DENIED else Status.OFFLINE
        }
        uiState = uiState.copy(status = status)
        if (state == TvClient.State.CONNECTED) {
            reconnectDelayMs = RECONNECT_DELAY_MS
            refreshVolume(delayMs = 0)
        }
        // Retrying after a denial would just pop the prompt up again; wait for a tap instead.
        if (state == TvClient.State.DISCONNECTED && resumed && !denied) {
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }

    override fun onTokenIssued(token: String) {
        ip?.let { prefs.edit { putString(tokenKey(it), token) } }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREF_IP = TvPrefs.IP
        private fun tokenKey(ip: String) = TvPrefs.token(ip)
        private fun macKey(ip: String) = TvPrefs.mac(ip)
        private fun nameKey(ip: String) = TvPrefs.name(ip)

        private const val RECONNECT_DELAY_MS = 3000L
        private const val TIZENTUBE_LOAD_MS = 3000L
        private const val PLAY_RESEND_MS = 4000L
        private const val PLAY_CONFIRM_TIMEOUT_MS = 25_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private val VOLUME_KEYS = setOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_MUTE")

        // "YouTube" opens TizenBrew (which runs the ad-free TizenTube) instead of the stock app.
        // Both IDs are fixed per app, not per TV.
        private val APPS = mapOf(
            TvApp.YOUTUBE to TvClient.TvApp("xvvl3S1bvH.TizenBrewStandalone", "YouTube"),
            TvApp.HOTSTAR to TvClient.TvApp("3201711015117", "Hotstar"),
        )
    }
}
