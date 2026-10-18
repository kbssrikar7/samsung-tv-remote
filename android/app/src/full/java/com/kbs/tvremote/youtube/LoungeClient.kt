package com.kbs.tvremote.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Plays videos on the TV's YouTube app through YouTube's "lounge" API - the same channel the
 * YouTube phone app uses after "Link with TV code". Works with TizenTube because it is the
 * YouTube TV web app. Ported from pyytlounge (https://github.com/FabioGNR/pyytlounge).
 *
 * Pairing yields a stable screenId (persisted); the short-lived lounge token and the bind
 * session are recreated on demand. All calls are blocking: use a background dispatcher.
 */
class LoungeClient(private val http: OkHttpClient, private val prefs: SharedPreferences) {

    @Volatile private var loungeToken: String? = null
    @Volatile private var sid: String? = null
    @Volatile private var gsessionId: String? = null
    @Volatile private var lastEventId = 0
    private var requestId = 0

    /** Long-poll requests stay open for minutes, so they can't share the app's call timeout. */
    private val longPoll = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    @Volatile private var watchCall: Call? = null

    private val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }
    }

    val isLinked: Boolean get() = prefs.getString(KEY_SCREEN_ID, null) != null

    /** Links to the TV using the 12-digit code from YouTube → Settings → Link with TV code. */
    @Throws(IOException::class)
    fun pair(code: String) {
        val json = postForm(
            "$BASE/pairing/get_screen",
            FormBody.Builder().add("pairing_code", code.filter(Char::isDigit)).build(),
        ) ?: throw IOException("That code didn't work. Check the numbers on the TV and try again.")
        val screen = json.getJSONObject("screen")
        prefs.edit { putString(KEY_SCREEN_ID, screen.getString("screenId")) }
        loungeToken = screen.getString("loungeToken")
        dropSession()
    }

    fun unlink() {
        prefs.edit { remove(KEY_SCREEN_ID) }
        loungeToken = null
        dropSession()
    }

    @Throws(IOException::class)
    fun playVideo(videoId: String) = command("setPlaylist", mapOf("videoId" to videoId))

    /** Plays [videoId] right after the current video. */
    @Throws(IOException::class)
    fun playNext(videoId: String) = command("insertVideo", mapOf("videoId" to videoId, "videoSources" to "XX"))

    /** Adds [videoId] to the end of the TV's queue. */
    @Throws(IOException::class)
    fun addToQueue(videoId: String) = command("addVideo", mapOf("videoId" to videoId, "videoSources" to "XX"))

    @Throws(IOException::class)
    fun play() = command("play")

    @Throws(IOException::class)
    fun pause() = command("pause")

    @Throws(IOException::class)
    fun seekTo(seconds: Double) = command("seekTo", mapOf("newTime" to seconds.toInt().toString()))

    /**
     * Blocking loop that follows playback on the TV and reports it through [onPlayback] (null when
     * nothing is playing or YouTube closed). Runs until [stopWatching] is called; reconnects on
     * expired sessions and network errors.
     */
    fun watch(onPlayback: (Playback?) -> Unit) {
        watching = true
        var retryDelay = RETRY_DELAY_MS
        while (watching) {
            try {
                if (sid == null) synchronized(this) { if (sid == null) connect() }
                command("getNowPlaying")
                val url = sessionUrl().newBuilder()
                    .addQueryParameter("RID", "rpc")
                    .addQueryParameter("CI", "0")
                    .addQueryParameter("TYPE", "xmlhttp")
                    .build()
                val call = longPoll.newCall(Request.Builder().url(url).build())
                watchCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 401) loungeToken = null
                        dropSession()
                        return@use
                    }
                    readChunks(response.body!!.source()) { events -> handleEvents(events, onPlayback) }
                    retryDelay = RETRY_DELAY_MS
                }
            } catch (e: IOException) {
                if (!watching) break
                dropSession()
                // Back off while offline so a dead network doesn't keep the radio awake.
                Thread.sleep(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    fun stopWatching() {
        watching = false
        watchCall?.cancel()
    }

    @Volatile private var watching = false

    /** The stream is "length\n<json chunk>" repeated; each chunk is [[eventId, [type, args...]], ...]. */
    private fun readChunks(source: okio.BufferedSource, onChunk: (JSONArray) -> Unit) {
        while (watching) {
            val header = source.readUtf8Line() ?: return
            val length = header.trim().toIntOrNull() ?: continue
            // Length counts UTF-16 characters of the chunk, newlines included.
            val chunk = StringBuilder()
            while (chunk.length < length) {
                val line = source.readUtf8Line() ?: return
                chunk.append(line).append('\n')
            }
            runCatching { JSONArray(chunk.toString()) }.getOrNull()?.let(onChunk)
        }
    }

    private fun handleEvents(events: JSONArray, onPlayback: (Playback?) -> Unit) {
        for (i in 0 until events.length()) {
            val entry = events.optJSONArray(i) ?: continue
            lastEventId = maxOf(lastEventId, entry.optInt(0))
            val event = entry.optJSONArray(1) ?: continue
            val data = event.optJSONObject(1)
            when (event.optString(0)) {
                "nowPlaying" -> {
                    val videoId = data?.optString("videoId").orEmpty()
                    onPlayback(if (videoId.isEmpty()) null else playback(videoId, data!!))
                }
                "onStateChange" -> {
                    val videoId = lastVideoId ?: continue
                    onPlayback(playback(videoId, data ?: continue))
                }
                "loungeScreenDisconnected" -> {
                    lastVideoId = null
                    onPlayback(null)
                }
            }
        }
    }

    @Volatile private var lastVideoId: String? = null

    private fun playback(videoId: String, data: JSONObject): Playback {
        lastVideoId = videoId
        // State codes from the YouTube player: 1 playing, 2 paused, 3 buffering, 0 ended, -1 stopped.
        val state = when (data.optString("state")) {
            "1" -> PlaybackState.PLAYING
            "2" -> PlaybackState.PAUSED
            "3", "5" -> PlaybackState.BUFFERING
            "0" -> PlaybackState.ENDED
            else -> PlaybackState.STOPPED
        }
        return Playback(
            videoId = videoId,
            state = state,
            position = data.optString("currentTime").toDoubleOrNull() ?: 0.0,
            duration = data.optString("duration").toDoubleOrNull() ?: 0.0,
        )
    }

    /**
     * Sends a lounge command, transparently refreshing an expired token or session once.
     */
    @Synchronized
    @Throws(IOException::class)
    fun command(name: String, params: Map<String, String> = emptyMap()) {
        repeat(2) { attempt ->
            if (sid == null) connect()
            val body = FormBody.Builder()
                .add("count", "1")
                .add("ofs", requestId.toString())
                .add("req0__sc", name)
                .apply { params.forEach { (k, v) -> add("req0_$k", v) } }
                .build()
            requestId++
            val url = sessionUrl().newBuilder().addQueryParameter("RID", requestId.toString()).build()
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (response.isSuccessful) return
                val reason = response.body?.string().orEmpty()
                // Expired session or token: rebuild and retry once.
                if (response.code == 401) loungeToken = null
                if (response.code in setOf(400, 401, 404, 410) && attempt == 0) {
                    dropSession()
                } else {
                    throw IOException("YouTube on the TV didn't respond (${response.code} $reason)")
                }
            }
        }
    }

    private fun connect() {
        val token = ensureToken()
        val body = FormBody.Builder()
            .add("app", "web")
            .add("mdx-version", "3")
            .add("name", DEVICE_NAME)
            // This remote's own id. Reusing the screen id (as pyytlounge does) makes the session
            // look like the TV itself and can push the real TV out of it.
            .add("id", deviceId)
            .add("device", "REMOTE_CONTROL")
            .add("capabilities", "que,dsdtr,atp,vsp")
            .add("magnaKey", "cloudPairedDevice")
            .add("ui", "false")
            .add("theme", "cl")
            .add("loungeIdToken", token)
            .build()
        val url = "$BASE/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error"
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            if (response.code == 401) {
                loungeToken = null
                throw IOException("YouTube link expired, please link again")
            }
            if (!response.isSuccessful) throw IOException("Couldn't reach YouTube on the TV (${response.code})")
            val text = response.body!!.string()
            // The reply is length-prefixed chunks of [[eventId, [type, args...]], ...].
            sid = Regex("""\["c","([^"]+)"""").find(text)?.groupValues?.get(1)
            gsessionId = Regex("""\["S","([^"]+)"""").find(text)?.groupValues?.get(1)
            lastEventId = Regex("""\[(\d+),\[""").findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
            requestId = 1
            if (sid == null || gsessionId == null) throw IOException("YouTube on the TV refused the connection")
        }
    }

    private fun sessionUrl() = "$BASE/bc/bind".toHttpUrl().newBuilder()
        .addQueryParameter("name", DEVICE_NAME)
        .addQueryParameter("loungeIdToken", loungeToken)
        .addQueryParameter("SID", sid)
        .addQueryParameter("AID", lastEventId.toString())
        .addQueryParameter("gsessionid", gsessionId)
        .addQueryParameter("device", "REMOTE_CONTROL")
        .addQueryParameter("app", "youtube-desktop")
        .addQueryParameter("VER", "8")
        .addQueryParameter("v", "2")
        .build()

    /** Returns a valid lounge token, refreshing it from the stored screenId if needed. */
    private fun ensureToken(): String {
        loungeToken?.let { return it }
        val screenId = prefs.getString(KEY_SCREEN_ID, null) ?: throw IOException("YouTube isn't linked yet")
        val json = postForm(
            "$BASE/pairing/get_lounge_token_batch",
            FormBody.Builder().add("screen_ids", screenId).build(),
        ) ?: throw IOException("Couldn't refresh the YouTube link")
        val token = json.getJSONArray("screens").getJSONObject(0).getString("loungeToken")
        loungeToken = token
        return token
    }

    private fun dropSession() {
        sid = null
        gsessionId = null
    }

    private fun postForm(url: String, body: FormBody): JSONObject? =
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) null else runCatching { JSONObject(response.body!!.string()) }.getOrNull()
        }

    private companion object {
        const val BASE = "https://www.youtube.com/api/lounge"
        const val DEVICE_NAME = "TV Remote"
        const val KEY_SCREEN_ID = "yt_screen_id"
        const val KEY_DEVICE_ID = "yt_device_id"
        const val RETRY_DELAY_MS = 3000L
        const val MAX_RETRY_DELAY_MS = 60_000L
    }
}
