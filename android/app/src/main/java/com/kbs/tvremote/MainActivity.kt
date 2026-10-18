package com.kbs.tvremote

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.kbs.tvremote.ui.RemoteActions
import com.kbs.tvremote.ui.RemoteScreen
import com.kbs.tvremote.ui.RemoteState
import com.kbs.tvremote.ui.RemoteTheme
import com.kbs.tvremote.ui.Status
import com.kbs.tvremote.ui.TvApp
import com.kbs.tvremote.ui.YouTubeActions
import com.kbs.tvremote.ui.YouTubeState
import com.kbs.tvremote.youtube.LoungeClient
import com.kbs.tvremote.youtube.Video
import com.kbs.tvremote.youtube.YouTubeSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), TvClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }
    private val client by lazy { TvClient(this, prefs) }

    // Internet traffic (YouTube) uses normal certificate validation, unlike the TV connection.
    private val web by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val youtubeSearch by lazy { YouTubeSearch(web) }
    private val lounge by lazy { LoungeClient(web, prefs) }

    private var resumed = false
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
            updateYouTube { copy(searching = true, searchedFor = query, error = null) }
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
        play = { video -> playOnTv(video) },
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
            }
        },
        unlink = {
            lounge.unlink()
            updateYouTube { YouTubeState(linked = false) }
        },
    )

    private val actions = RemoteActions(
        key = { key ->
            if (client.state == TvClient.State.DISCONNECTED) connect()
            client.sendKey(key)
        },
        launch = { app -> client.launchApp(APPS.getValue(app)) },
        power = { power() },
        retry = {
            client.disconnect()
            if (ip == null) findTv() else connect()
        },
        youtube = youtubeActions,
        findTv = { findTv() },
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
            youtube = YouTubeState(linked = lounge.isLinked),
        )
        setContent {
            RemoteTheme { RemoteScreen(uiState, actions) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (ip == null) findTv() else connect()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(reconnect)
        client.disconnect()
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
        client.sendKey(key)
        return true
    }

    /**
     * Plays [video] in the TV's YouTube (TizenTube). If YouTube isn't open on the TV, opens it first
     * and waits for it to come online in the lounge before sending the video.
     */
    private fun playOnTv(video: Video) {
        updateYouTube { copy(sendingId = video.id, error = null) }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!lounge.isScreenOnline()) {
                        client.launchApp(APPS.getValue(TvApp.YOUTUBE))
                        var online = false
                        for (i in 0 until 16) {
                            Thread.sleep(1500)
                            if (lounge.isScreenOnline()) { online = true; break }
                        }
                        // Give the app a moment to finish loading before handing it a video.
                        if (online) Thread.sleep(1500)
                    }
                    lounge.playVideo(video.id)
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
        // Retrying after a denial would just pop the prompt up again; wait for a tap instead.
        if (state == TvClient.State.DISCONNECTED && resumed && !denied) {
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, RECONNECT_DELAY_MS)
        }
    }

    override fun onTokenIssued(token: String) {
        ip?.let { prefs.edit { putString(tokenKey(it), token) } }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREF_IP = "ip"
        private fun tokenKey(ip: String) = "token_$ip"
        private fun macKey(ip: String) = "mac_$ip"
        private fun nameKey(ip: String) = "name_$ip"

        private const val RECONNECT_DELAY_MS = 3000L

        // "YouTube" opens TizenBrew (which runs the ad-free TizenTube) instead of the stock app.
        // Both IDs are fixed per app, not per TV.
        private val APPS = mapOf(
            TvApp.YOUTUBE to TvClient.TvApp("xvvl3S1bvH.TizenBrewStandalone", "YouTube"),
            TvApp.HOTSTAR to TvClient.TvApp("3201711015117", "Hotstar"),
        )
    }
}
