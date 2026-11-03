package com.kbs.tvremote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.kbs.tvremote.ui.YouTubeActions
import com.kbs.tvremote.ui.YouTubeState
import com.kbs.tvremote.youtube.Library
import com.kbs.tvremote.youtube.Playback
import com.kbs.tvremote.youtube.PlaybackState
import com.kbs.tvremote.youtube.Video
import com.kbs.tvremote.youtube.YouTubeFeature
import com.kbs.tvremote.youtube.YouTubeRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TvClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { TvPrefs.of(this) }
    private val tvNetwork by lazy { TvNetwork(this) }
    private val client by lazy { TvClient(this, prefs, tvNetwork) }
    private var networkObserver: java.io.Closeable? = null
    private var connectionJob: Job? = null
    private var recovery: Job? = null
    private var nextRecoveryAt = 0L
    internal var discoverTvs: suspend () -> List<TvDiscovery.FoundTv> = {
        withContext(Dispatchers.IO) { TvDiscovery.discover(applicationContext, tvNetwork) }
    }
    private val networkChanged = Runnable {
        if (resumed) {
            connectionJob?.cancel()
            discovery?.cancel()
            recovery?.cancel()
            client.disconnect()
            nextRecoveryAt = 0
            reconnectDelayMs = RECONNECT_DELAY_MS
            if (ip == null) findTv() else connect()
        }
    }

    // Internet traffic (YouTube) uses normal certificate validation, unlike the TV connection.
    private val web by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    /** In-app YouTube (GitHub build only); null in the Play build. */
    private val youtube: YouTubeRemote? by lazy { YouTubeFeature.create(web, prefs) }

    /** The in-app YouTube, for code that only runs when [youtubeInApp] is true. */
    private val yt: YouTubeRemote get() = checkNotNull(youtube)
    private val library by lazy { Library(prefs) }
    private var watchJob: Job? = null
    private var searchJob: Job? = null
    private var linkJob: Job? = null
    private var playJob: Job? = null
    private var appsJob: Job? = null
    private var volumeJob: Job? = null
    private var watchRevision = 0
    private var playRevision = 0
    /** Video sent after a cold start, completed once the TV reports playing it. */
    private var awaitedVideo: Pair<String, CompletableDeferred<Unit>>? = null
    private val volumeRefresh = Runnable {
        val selectedIp = ip
        if (resumed && client.state == TvClient.State.CONNECTED && selectedIp != null) {
            volumeJob?.cancel()
            volumeJob = lifecycleScope.launch {
                val volume = withContext(Dispatchers.IO) { client.volume(selectedIp) }
                if (ip == selectedIp && resumed) uiState = uiState.copy(volume = volume)
            }
        }
    }

    private var resumed = false
    private var appsDetected = false
    /** Grows while the TV stays unreachable so an off TV doesn't keep the radio busy. */
    private var reconnectDelayMs = RECONNECT_DELAY_MS

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RemoteNotification.show(this) else RemoteNotification.setEnabled(this, false)
        uiState = uiState.copy(notificationEnabled = RemoteNotification.isEnabled(this))
    }
    private val reconnect = Runnable { connect() }
    private var discovery: Job? = null

    /** The chosen TV's address, or null until one has been found or entered. */
    private val ip: String? get() = prefs.getString(PREF_IP, null)?.trim()?.takeIf(TvPrefs::isValidIp)

    private var uiState by mutableStateOf(
        RemoteState(tvName = "Samsung TV", status = Status.CONNECTING, ip = null, youtube = YouTubeState(linked = false))
    )

    private fun updateYouTube(change: YouTubeState.() -> YouTubeState) {
        uiState = uiState.copy(youtube = uiState.youtube.change())
    }

    private val youtubeActions = YouTubeActions(
        search = { query ->
            searchJob?.cancel()
            updateYouTube { copy(searching = true, searchedFor = query, error = null, recentSearches = library.addRecentSearch(query)) }
            searchJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { cancellableResult { yt.search(query) } }
                updateYouTube {
                    copy(
                        searching = false,
                        results = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.let { "Couldn't search. Check the phone's internet." },
                    )
                }
            }
        },
        clear = {
            searchJob?.cancel()
            updateYouTube { copy(searching = false, searchedFor = null, results = emptyList(), error = null) }
        },
        play = { video -> playOnTv(video) },
        playNext = { video -> queue(video, "Will play next") { yt.playNext(it) } },
        addToQueue = { video -> queue(video, "Added to the queue") { yt.addToQueue(it) } },
        toggleFavourite = { video -> updateYouTube { copy(favourites = library.toggleFavourite(video)) } },
        link = { code ->
            linkJob?.cancel()
            updateYouTube { copy(linking = true, error = null) }
            linkJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { cancellableResult { yt.link(code) } }
                updateYouTube {
                    copy(
                        linking = false,
                        linked = yt.isLinked,
                        error = result.exceptionOrNull()?.let { it.message ?: "Couldn't connect to YouTube" },
                    )
                }
                if (yt.isLinked) startWatching()
            }
        },
        unlink = { unlinkYouTube() },
    )

    private val actions = RemoteActions(
        key = { key -> sendKey(key) },
        launch = { app ->
            val selectedIp = ip
            if (app.kind == TvApps.Kind.YOUTUBE) openYouTube()
            else inBackground { client.launchApp(TvClient.TvApp(app.id, app.name), selectedIp) }
        },
        searchOnPhone = { query -> searchOnPhone(query) },
        power = { power() },
        retry = {
            client.disconnect()
            if (ip == null || uiState.status == Status.NOT_FOUND) findTv() else connect()
        },
        youtube = youtubeActions,
        findTv = { findTv() },
        setNotification = { enabled -> setNotification(enabled) },
        nowPlaying = NowPlayingActions(
            togglePlay = { togglePlay() },
            seekTo = { seconds -> inBackground { yt.seekTo(seconds) } },
        ),
        chooseTv = { tv -> useTv(tv.ip, tv.name, tv.identity) },
        saveIp = { newIp ->
            if (TvPrefs.isValidIp(newIp)) useTv(newIp.trim(), null)
            else toast("Enter the four-part TV address, for example 192.168.1.10")
        },
        forgetPairing = {
            ip?.let { current -> prefs.edit {
                remove(tokenKey(current)); remove(TvClient.pinKey(current)); remove("legacy_$current")
                remove(TvPrefs.id(current)); remove(macKey(current))
            } }
            client.disconnect()
            connect()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so ask for light system-bar icons on every Android version
        // (older ones otherwise draw dark icons and a light navigation bar).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        installedApps = ip?.let { TvApps.load(prefs, it) }.orEmpty()
        uiState = uiState.copy(
            tvName = ip?.let { prefs.getString(nameKey(it), null) } ?: uiState.tvName,
            ip = ip,
            apps = installedApps.visibleShortcuts(),
            youtube = YouTubeState(
                linked = youtube?.isLinked == true,
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
        networkObserver = tvNetwork.observe {
            main.removeCallbacks(networkChanged)
            main.postDelayed(networkChanged, 350)
        }
        reconnectDelayMs = RECONNECT_DELAY_MS
        if (ip == null) findTv() else connect()
        if (youtube?.isLinked == true) startWatching()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        networkObserver?.close()
        networkObserver = null
        main.removeCallbacks(networkChanged)
        connectionJob?.cancel()
        recovery?.cancel()
        discovery?.cancel()
        if (appsJob?.isActive == true) {
            appsJob?.cancel()
            appsDetected = false
        }
        playJob?.cancel()
        uiState = uiState.copy(youtube = uiState.youtube.copy(sendingId = null))
        main.removeCallbacks(volumeRefresh)
        volumeJob?.cancel()
        main.removeCallbacks(reconnect)
        client.disconnect()
        stopWatching()
    }

    private fun setNotification(enabled: Boolean) {
        RemoteNotification.setEnabled(this, enabled)
        // hasPermission is always true below Android 13, so this only asks where it is needed.
        if (enabled && !RemoteNotification.hasPermission(this)) {
            // After repeated denials Android no longer shows the prompt and the switch would do
            // nothing, so open the app's notification settings instead.
            val blocked = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
            if (blocked) {
                RemoteNotification.setEnabled(this, false)
                toast("Turn on notifications for this app to show the buttons")
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
        if (client.state != TvClient.State.CONNECTED) {
            if (client.state == TvClient.State.DISCONNECTED) {
                toast("Connect to your TV first. Follow the steps above.")
                if (uiState.status != Status.DENIED && uiState.detail !in setOf(
                        TvClient.CERTIFICATE_CHANGED, TvClient.PAIRING_TIMEOUT, TvClient.UNSUPPORTED_REMOTE)) {
                    if (ip == null) findTv() else connect()
                }
            }
            return
        }
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
        if (!resumed || youtube?.isLinked != true) return
        if (watchJob?.isActive == true) return
        val revision = ++watchRevision
        watchJob = lifecycleScope.launch(Dispatchers.IO) {
            runInterruptible { yt.watch { playback -> main.post {
                if (watchRevision == revision && resumed && youtube?.isLinked == true) onPlayback(playback)
            } } }
        }
    }

    private fun stopWatching() {
        watchRevision++
        youtube?.stopWatching()
        watchJob?.cancel()
        watchJob = null
    }

    private fun unlinkYouTube() {
        searchJob?.cancel()
        linkJob?.cancel()
        playJob?.cancel()
        stopWatching()
        youtube?.unlink()
        updateYouTube { YouTubeState(linked = false, favourites = favourites, recentSearches = recentSearches) }
        uiState = uiState.copy(nowPlaying = null)
    }

    private fun onPlayback(playback: Playback?) {
        awaitedVideo?.let { (id, confirmed) -> if (playback?.videoId == id) confirmed.complete(Unit) }
        if (playback == null || playback.state == PlaybackState.STOPPED) {
            uiState = uiState.copy(nowPlaying = null)
            return
        }
        val previous = uiState.nowPlaying
        val known = previous?.video?.takeIf { it.id == playback.videoId }
            ?: (uiState.youtube.results + uiState.youtube.favourites).firstOrNull { it.id == playback.videoId }
        uiState = uiState.copy(nowPlaying = NowPlaying(known, playback, System.currentTimeMillis()))
        if (known == null) {
            lifecycleScope.launch {
                val details = withContext(Dispatchers.IO) { yt.details(playback.videoId) } ?: return@launch
                val current = uiState.nowPlaying ?: return@launch
                if (current.playback.videoId == details.id) uiState = uiState.copy(nowPlaying = current.copy(video = details))
            }
        }
    }

    private fun togglePlay() {
        val current = uiState.nowPlaying ?: return
        val playing = current.playback.state == PlaybackState.PLAYING || current.playback.state == PlaybackState.BUFFERING
        // Show the change at once; the TV's own report follows and corrects it if needed.
        val state = if (playing) PlaybackState.PAUSED else PlaybackState.PLAYING
        uiState = uiState.copy(nowPlaying = current.copy(playback = current.playback.copy(state = state)))
        inBackground { if (playing) yt.pause() else yt.resume() }
    }

    private fun queue(video: Video, done: String, send: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { cancellableResult { send(video.id) } }
            toast(if (result.isSuccess) done else result.exceptionOrNull()?.message ?: "Couldn't reach YouTube on the TV")
        }
    }

    private fun inBackground(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            cancellableResult { block() }.onFailure { e ->
                withContext(Dispatchers.Main) { toast(e.message ?: "Couldn't reach YouTube on the TV") }
            }
        }
    }

    /** True in the GitHub build, which searches YouTube itself and plays videos on the TV. */
    private val youtubeInApp get() = youtube != null

    /** All installed apps found on the TV, including TizenBrew, which isn't shown as a button. */
    private var installedApps: List<TvShortcut> = emptyList()

    private fun List<TvShortcut>.visibleShortcuts() = filter { it.kind != TvApps.Kind.TIZENBREW }

    /** TizenBrew (running TizenTube) when the GitHub build finds it on the TV, else stock YouTube. */
    private val youtubeOnTv: String
        get() = if (youtubeInApp && installedApps.any { it.kind == TvApps.Kind.TIZENBREW }) TvApps.TIZENBREW else TvApps.YOUTUBE

    /** Asks the TV which apps are installed, once per connection, and remembers them per TV. */
    private fun detectApps(ip: String) {
        appsJob?.cancel()
        appsJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { TvApps.detect(client, ip, includeTizenBrew = youtubeInApp) }
            if (this@MainActivity.ip != ip) return@launch
            installedApps = found
            TvApps.save(prefs, ip, found)
            uiState = uiState.copy(apps = found.visibleShortcuts())
        }
    }

    /**
     * Play-store build: opens the official YouTube app's search on the phone (which can cast to
     * the TV itself), or the YouTube website if the app isn't installed.
     */
    private fun searchOnPhone(query: String) {
        try {
            startActivity(YouTubeApp.searchIntent(query))
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(YouTubeApp.webSearchIntent(query)) }
                .onFailure { toast("YouTube isn't available on this phone") }
        }
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        if (!resumed || discovery?.isActive == true || recovery?.isActive == true) return
        if (connectionJob?.isActive == true || client.state != TvClient.State.DISCONNECTED) return
        val selectedIp = ip ?: return
        if (tvNetwork.current() == null) {
            uiState = uiState.copy(status = Status.OFFLINE, detail = TvNetwork.WIFI_REQUIRED, volume = null)
            return
        }
        uiState = uiState.copy(ip = selectedIp, status = Status.CONNECTING, detail = null)
        connectionJob = lifecycleScope.launch {
            val expected = TvPrefs.identity(prefs, selectedIp)
            val info = withContext(Dispatchers.IO) { client.deviceInfo(selectedIp) }
            if (ip != selectedIp || !resumed) return@launch
            val verified = info != null && (!expected.known || expected.matches(info.identity))
            if (expected.known && info?.identity?.known == true && !verified) {
                if (!recoverTv()) scheduleReconnect()
                return@launch
            }
            // If REST is unavailable, only the previously pinned secure transport may reuse
            // this TV's credentials. Never fall back to an unidentified plain socket.
            if (expected.known && !verified && !prefs.contains(TvClient.pinKey(selectedIp))) {
                if (!recoverTv()) scheduleReconnect()
                return@launch
            }
            if (verified) {
                TvPrefs.remember(prefs, info!!)
                uiState = uiState.copy(tvName = info.name)
            }
            client.connect(selectedIp, prefs.getString(tokenKey(selectedIp), null), allowPlain = !expected.known || verified)
        }
    }

    private fun scheduleReconnect() {
        if (!resumed || tvNetwork.current() == null) return
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun recoverTv(): Boolean {
        val oldIp = ip ?: return false
        val expected = TvPrefs.identity(prefs, oldIp)
        if (!expected.known || tvNetwork.current() == null) return false
        if (recovery?.isActive == true) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextRecoveryAt) {
            uiState = uiState.copy(status = Status.OFFLINE, detail = "Your saved TV is unavailable. We'll keep looking; check that it is on.")
            return false
        }
        nextRecoveryAt = now + RECOVERY_INTERVAL_MS
        main.removeCallbacks(reconnect)
        uiState = uiState.copy(status = Status.SEARCHING, detail = "Looking for your saved TV…", volume = null)
        recovery = lifecycleScope.launch {
            val found = cancellableResult { discoverTvs() }.getOrDefault(emptyList())
            recovery = null
            if (ip != oldIp || !resumed) return@launch
            val tv = TvRecovery.match(expected, found)
            if (tv != null && TvRecovery.relocate(prefs, oldIp, tv)) {
                useTv(tv.ip, tv.name, tv.identity)
            } else {
                uiState = uiState.copy(status = Status.OFFLINE, detail = "Can't find your saved TV. Turn it on and check the home Wi-Fi.")
                scheduleReconnect()
            }
        }
        return true
    }

    /** Searches the Wi-Fi for Samsung TVs; picks the only one found or lets the user choose. */
    private fun findTv() {
        if (discovery?.isActive == true) return
        connectionJob?.cancel()
        recovery?.cancel()
        main.removeCallbacks(reconnect)
        client.disconnect()
        if (tvNetwork.current() == null) {
            uiState = uiState.copy(status = Status.OFFLINE, detail = TvNetwork.WIFI_REQUIRED)
            return
        }
        uiState = uiState.copy(status = Status.SEARCHING, detail = null, foundTvs = emptyList())
        discovery = lifecycleScope.launch {
            val found = cancellableResult { discoverTvs() }.getOrDefault(emptyList())
            discovery = null
            val oldIp = ip
            val expected = oldIp?.let { TvPrefs.identity(prefs, it) }
            val saved = expected?.let { TvRecovery.match(it, found) }
            if (oldIp != null && saved != null && TvRecovery.relocate(prefs, oldIp, saved)) {
                useTv(saved.ip, saved.name, saved.identity)
                return@launch
            }
            when (found.size) {
                0 -> uiState = uiState.copy(status = Status.NOT_FOUND)
                1 -> if (expected?.known == true) {
                    uiState = uiState.copy(status = Status.NOT_FOUND, foundTvs = found)
                } else useTv(found[0].ip, found[0].name, found[0].identity)
                else -> uiState = uiState.copy(status = Status.NOT_FOUND, foundTvs = found)
            }
        }
    }

    private fun useTv(newIp: String, name: String?, identity: TvIdentity? = null) {
        val oldIp = ip
        if (oldIp != null && oldIp != newIp && identity != null && TvPrefs.identity(prefs, oldIp).matches(identity)) {
            TvRecovery.relocate(prefs, oldIp, TvDiscovery.FoundTv(newIp, name.orEmpty(), identity.deviceId,
                identity.mac?.chunked(2)?.joinToString(":")))
        }
        connectionJob?.cancel()
        recovery?.cancel()
        discovery?.cancel()
        discovery = null
        appsJob?.cancel()
        volumeJob?.cancel()
        main.removeCallbacks(volumeRefresh)
        val sameIdentity = identity != null && ip?.let { TvPrefs.identity(prefs, it).matches(identity) } == true
        if (ip != null && (ip != newIp || (identity?.known == true && !sameIdentity))) unlinkYouTube()
        prefs.edit {
            putString(PREF_IP, newIp)
            if (name != null) putString(nameKey(newIp), name)
        }
        if (identity != null) TvPrefs.remember(prefs, TvDiscovery.FoundTv(newIp, name.orEmpty(), identity.deviceId,
            identity.mac?.chunked(2)?.joinToString(":")))
        installedApps = TvApps.load(prefs, newIp)
        uiState = uiState.copy(ip = newIp, foundTvs = emptyList(), volume = null, tvName = name ?: "Samsung TV", apps = installedApps.visibleShortcuts())
        appsDetected = false
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
     * Makes sure TizenTube is in front on the TV and returns true if it had to be started.
     * A freshly started TizenBrew stops on its module list unless its autolaunch is set, so the app
     * presses Down and OK to enter TizenTube. Keys are only ever sent right after the app started
     * TizenBrew itself, so they can't disturb something playing.
     */
    private suspend fun ensureYouTubeOpen(): Boolean {
        val selectedIp = ip ?: throw IOException("Choose a TV first")
        val app = TvClient.TvApp(youtubeOnTv, "YouTube")
        val before = withContext(Dispatchers.IO) { client.appStatus(app, selectedIp) }
            ?: throw IOException("Can't reach the TV. Is it on?")
        if (before.running) {
            if (!before.visible) withContext(Dispatchers.IO) { client.launchApp(app, selectedIp) }
            return false
        }
        withContext(Dispatchers.IO) { client.launchApp(app, selectedIp) }
        waitUntilInFront(app, selectedIp)
        if (app.id == TvApps.TIZENBREW) {
            // TizenBrew starts with its top bar highlighted, so OK alone does nothing: move down
            // onto TizenTube (the first module) and open it.
            pressKey("KEY_DOWN")
            delay(800)
            pressKey("KEY_ENTER")
        }
        return true
    }

    /**
     * Sends only while this playback operation is active and the app's remote is authorized.
     */
    private suspend fun pressKey(key: String) {
        coroutineContext.ensureActive()
        if (!client.sendKey(key)) throw IOException("Connect the Samsung remote before opening TizenTube")
    }

    /** TizenBrew can restart during startup; wait until it stays in front without blocking a thread. */
    private suspend fun waitUntilInFront(app: TvClient.TvApp, selectedIp: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        var steady = 0
        while (steady < 2) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("YouTube didn't open on the TV")
            delay(1000)
            steady = if (withContext(Dispatchers.IO) { client.appStatus(app, selectedIp) }?.visible == true) steady + 1 else 0
        }
        delay(1500) // let the module list render
    }

    private fun openYouTube() {
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            cancellableResult { ensureYouTubeOpen() }.onFailure { e ->
                toast(e.message ?: "Couldn't open YouTube")
            }
        }
    }

    /** Plays [video] in the TV's YouTube (TizenTube or stock), opening it first when needed. */
    private fun playOnTv(video: Video) {
        playJob?.cancel()
        val revision = ++playRevision
        updateYouTube { copy(sendingId = video.id, error = null) }
        startWatching()
        playJob = lifecycleScope.launch {
            try {
                val result = cancellableResult {
                    val coldStart = ensureYouTubeOpen()
                    if (!coldStart) {
                        withContext(Dispatchers.IO) { yt.play(video.id) }
                        return@cancellableResult
                    }
                    // A just-started TizenTube may not be listening yet, and a video sent too early is
                    // silently dropped. Resend until the TV reports it playing.
                    delay(TIZENTUBE_LOAD_MS)
                    val confirmed = CompletableDeferred<Unit>()
                    awaitedVideo = video.id to confirmed
                    try {
                        val deadline = android.os.SystemClock.elapsedRealtime() + PLAY_CONFIRM_TIMEOUT_MS
                        while (true) {
                            withContext(Dispatchers.IO) { yt.play(video.id) }
                            if (withTimeoutOrNull(PLAY_RESEND_MS) { confirmed.await() } != null) break
                            if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("YouTube on the TV didn't start the video")
                        }
                    } finally {
                        if (awaitedVideo?.second === confirmed) awaitedVideo = null
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
            } finally {
                if (revision == playRevision) updateYouTube { copy(sendingId = null) }
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
        if (!client.wake(mac)) {
            toast("The TV's wake address is missing or invalid. Connect with the TV on first.")
            return
        }
        toast("Turning the TV on…")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, 4000)
    }

    override fun onStateChanged(state: TvClient.State, detail: String?) {
        if (!resumed || discovery?.isActive == true || recovery?.isActive == true) return
        if (state == TvClient.State.DISCONNECTED && connectionJob?.isActive == true) return
        if (tvNetwork.current() == null) {
            uiState = uiState.copy(status = Status.OFFLINE, detail = TvNetwork.WIFI_REQUIRED, volume = null)
            return
        }
        val denied = detail == TvClient.DENIED
        val status = when (state) {
            TvClient.State.CONNECTED -> Status.READY
            TvClient.State.CONNECTING -> Status.CONNECTING
            TvClient.State.WAITING_FOR_ALLOW -> Status.ALLOW
            TvClient.State.DISCONNECTED -> if (denied) Status.DENIED else Status.OFFLINE
        }
        uiState = uiState.copy(status = status, volume = if (state == TvClient.State.CONNECTED) uiState.volume else null, detail = if (denied)
            "Your TV did not allow this phone. Open the TV's External Device Manager → Device Connect Manager and allow this remote. Menu names vary by model."
            else detail)
        if (state == TvClient.State.CONNECTED) {
            nextRecoveryAt = 0
            reconnectDelayMs = RECONNECT_DELAY_MS
            refreshVolume(delayMs = 0)
            if (!appsDetected) {
                appsDetected = true
                ip?.let { detectApps(it) }
            }
        }
        // Retrying after a denial would just pop the prompt up again; wait for a tap instead.
        if (state == TvClient.State.DISCONNECTED && resumed && !denied &&
            detail != TvClient.CERTIFICATE_CHANGED && detail != TvClient.PAIRING_TIMEOUT &&
            detail != TvClient.UNSUPPORTED_REMOTE &&
            discovery?.isActive != true) {
            if (!recoverTv()) scheduleReconnect()
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
        private const val RECOVERY_INTERVAL_MS = 30_000L
        private const val TIZENTUBE_LOAD_MS = 3000L
        private const val PLAY_RESEND_MS = 4000L
        private const val PLAY_CONFIRM_TIMEOUT_MS = 25_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private val VOLUME_KEYS = setOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_MUTE")
    }
}
