package com.kbs.tvremote.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays videos on the TV's YouTube app through YouTube's "lounge" API - the same channel the
 * YouTube phone app uses after "Link with TV code". Works with TizenTube because it is the
 * YouTube TV web app. Ported from pyytlounge (https://github.com/FabioGNR/pyytlounge).
 *
 * Pairing yields a stable screenId (persisted); the short-lived lounge token and the bind
 * session are recreated on demand. All calls are blocking: use a background dispatcher.
 */
class LoungeClient(
    private val http: OkHttpClient,
    private val prefs: SharedPreferences,
    private val baseUrl: String = BASE,
) {

    private val linkLock = Any()
    private val linkRevision = AtomicLong()

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

    private class Watch {
        val stopped = CountDownLatch(1)
        @Volatile var call: Call? = null
        val active get() = stopped.count != 0L
        fun stop() { stopped.countDown(); call?.cancel() }
    }
    private val watcher = AtomicReference<Watch?>()

    private val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }
    }

    val isLinked: Boolean get() = !prefs.getString(KEY_SCREEN_ID, null).isNullOrBlank()

    /** Links to the TV using the 12-digit code from YouTube → Settings → Link with TV code. */
    @Throws(IOException::class)
    fun pair(code: String) {
        val digits = code.filter { it in '0'..'9' }
        if (digits.length != 12) throw IOException("Enter the 12 numbers shown on your TV")
        val revision = linkRevision.incrementAndGet()
        stopWatching()
        val json = postForm(
            "$baseUrl/pairing/get_screen",
            FormBody.Builder().add("pairing_code", digits).build(),
        ) ?: throw IOException("That code didn't work. Check the numbers on the TV and try again.")
        val screen = json.optJSONObject("screen")
        val screenId = screen?.optString("screenId").orEmpty()
        val token = screen?.optString("loungeToken").orEmpty()
        if (screenId.isBlank() || token.isBlank()) throw IOException("YouTube returned an incomplete TV link. Try linking again.")
        synchronized(linkLock) {
            if (linkRevision.get() != revision) throw IOException("YouTube linking was cancelled")
            prefs.edit { putString(KEY_SCREEN_ID, screenId) }
            loungeToken = token
            dropSession()
            lastVideoId = null
            lastPlayback = null
        }
    }

    fun unlink() {
        stopWatching()
        synchronized(linkLock) {
            linkRevision.incrementAndGet()
            prefs.edit { remove(KEY_SCREEN_ID) }
            loungeToken = null
            dropSession()
            lastVideoId = null
            lastPlayback = null
        }
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
    fun seekTo(seconds: Double) {
        if (!seconds.isFinite() || seconds < 0) throw IOException("Invalid playback position")
        command("seekTo", mapOf("newTime" to seconds.toLong().toString()))
    }

    /**
     * Blocking loop that follows playback on the TV and reports it through [onPlayback] (null when
     * nothing is playing or YouTube closed). Runs until [stopWatching] is called; reconnects on
     * expired sessions and network errors.
     */
    fun watch(onPlayback: (Playback?) -> Unit) {
        val watch = Watch()
        watcher.getAndSet(watch)?.stop()
        var retryDelay = RETRY_DELAY_MS
        try {
            while (watch.active && isLinked) {
                var observedSid: String? = null
                val revision = linkRevision.get()
                try {
                    sendCommand("getNowPlaying", emptyMap(), watch)
                    if (!watch.active) break
                    val url = synchronized(this) {
                        observedSid = sid
                        sessionUrl().newBuilder()
                        .addQueryParameter("RID", "rpc")
                        .addQueryParameter("CI", "0")
                        .addQueryParameter("TYPE", "xmlhttp")
                        .build()
                    }
                    val call = longPoll.newCall(Request.Builder().url(url).build())
                    watch.call = call
                    if (!watch.active) { call.cancel(); break }
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code == 401) synchronized(linkLock) {
                                if (watch.active && linkRevision.get() == revision) loungeToken = null
                            }
                            throw IOException("YouTube playback connection failed (${response.code})")
                        }
                        while (watch.active) {
                            val events = LoungeFrames.read(response.body!!.source()) ?: break
                            if (watch.active) handleEvents(events, { watch.active && linkRevision.get() == revision }, onPlayback)
                        }
                        retryDelay = RETRY_DELAY_MS
                    }
                } catch (e: IOException) {
                    if (!watch.active) break
                    synchronized(this) { if (watch.active && sid == observedSid) dropSession() }
                } finally {
                    watch.call = null
                }
                // Both failed polls and immediately closed successful polls need backoff.
                // A stopped watcher wakes immediately, even during the longest offline backoff.
                if (watch.stopped.await(retryDelay, TimeUnit.MILLISECONDS)) break
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        } finally { watch.stop(); watcher.compareAndSet(watch, null) }
    }

    fun stopWatching() {
        watcher.getAndSet(null)?.stop()
    }

    private data class PlaybackUpdate(val value: Playback?, val disconnected: Boolean = false)

    private fun handleEvents(events: JSONArray, active: () -> Boolean, onPlayback: (Playback?) -> Unit) {
        for (i in 0 until events.length()) {
            val entry = events.optJSONArray(i) ?: continue
            val event = entry.optJSONArray(1) ?: continue
            val data = event.optJSONObject(1)
            val update = synchronized(linkLock) {
                if (!active()) return
                lastEventId = maxOf(lastEventId, entry.optInt(0))
                when (event.optString(0)) {
                    "nowPlaying" -> {
                        val videoId = data?.optString("videoId").orEmpty()
                        if (videoId.isEmpty()) { lastVideoId = null; lastPlayback = null }
                        PlaybackUpdate(if (videoId.isEmpty()) null else playback(videoId, data!!))
                    }
                    "onStateChange" -> {
                        val videoId = lastVideoId
                        if (videoId == null || data == null) null else PlaybackUpdate(playback(videoId, data))
                    }
                    "loungeScreenDisconnected" -> {
                        lastVideoId = null
                        lastPlayback = null
                        PlaybackUpdate(null, disconnected = true)
                    }
                    else -> null
                }
            }
            if (update != null && active()) onPlayback(update.value)
            if (update?.disconnected == true) throw IOException("YouTube on the TV disconnected")
        }
    }

    @Volatile private var lastVideoId: String? = null
    @Volatile private var lastPlayback: Playback? = null

    private fun playback(videoId: String, data: JSONObject): Playback {
        val previous = lastPlayback?.takeIf { it.videoId == videoId }
        lastVideoId = videoId
        // State codes from the YouTube player: 1 playing, 2 paused, 3 buffering, 0 ended, -1 stopped.
        val state = when (data.optString("state")) {
            "1" -> PlaybackState.PLAYING
            "2" -> PlaybackState.PAUSED
            "3", "5" -> PlaybackState.BUFFERING
            "0" -> PlaybackState.ENDED
            else -> if (!data.has("state")) previous?.state ?: PlaybackState.STOPPED else PlaybackState.STOPPED
        }
        return Playback(
            videoId = videoId,
            state = state,
            position = data.optString("currentTime").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: previous?.position ?: 0.0,
            duration = data.optString("duration").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: previous?.duration ?: 0.0,
        ).also { lastPlayback = it }
    }

    /**
     * Sends a lounge command, transparently refreshing an expired token or session once.
     */
    @Throws(IOException::class)
    fun command(name: String, params: Map<String, String> = emptyMap()) = sendCommand(name, params, null)

    @Synchronized
    private fun sendCommand(name: String, params: Map<String, String>, watch: Watch?) {
        val revision = linkRevision.get()
        repeat(2) { attempt ->
            synchronized(linkLock) {
                if (linkRevision.get() != revision || !isLinked) throw IOException("YouTube isn't linked to this TV")
            }
            if (sid == null) connect(watch)
            val body = FormBody.Builder()
                .add("count", "1")
                .add("ofs", requestId.toString())
                .add("req0__sc", name)
                .apply { params.forEach { (k, v) -> add("req0_$k", v) } }
                .build()
            requestId++
            val url = sessionUrl().newBuilder().addQueryParameter("RID", requestId.toString()).build()
            response(Request.Builder().url(url).post(body).build(), watch) { response ->
                synchronized(linkLock) {
                if (linkRevision.get() != revision) throw IOException("YouTube link changed")
                if (response.isSuccessful) return
                // Expired session or token: rebuild and retry once.
                if (response.code == 401) loungeToken = null
                if (response.code in setOf(400, 401, 404, 410) && attempt == 0) {
                    dropSession()
                } else {
                    throw IOException("YouTube on the TV didn't respond (${response.code})")
                }
                }
            }
        }
    }

    private fun connect(watch: Watch?) {
        val revision = linkRevision.get()
        val token = ensureToken(watch)
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
        val url = "$baseUrl/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error"
        response(Request.Builder().url(url).post(body).build(), watch) { response ->
            if (response.code == 401) {
                synchronized(linkLock) { if (linkRevision.get() == revision) loungeToken = null }
                throw IOException("YouTube link expired, please link again")
            }
            if (!response.isSuccessful) throw IOException("Couldn't reach YouTube on the TV (${response.code})")
            val text = response.body!!.string()
            // The reply is length-prefixed chunks of [[eventId, [type, args...]], ...].
            val newSid = Regex("""\["c","([^"]+)"""").find(text)?.groupValues?.get(1)
            val newGsessionId = Regex("""\["S","([^"]+)"""").find(text)?.groupValues?.get(1)
            val eventId = Regex("""\[(\d+),\[""").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
            if (newSid == null || newGsessionId == null) throw IOException("YouTube on the TV refused the connection")
            synchronized(linkLock) {
                if (linkRevision.get() != revision || !isLinked) throw IOException("YouTube link changed")
                sid = newSid
                gsessionId = newGsessionId
                lastEventId = eventId
                requestId = 1
            }
        }
    }

    private fun sessionUrl() = "$baseUrl/bc/bind".toHttpUrl().newBuilder()
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
    private fun ensureToken(watch: Watch?): String {
        val revision = linkRevision.get()
        loungeToken?.let { return it }
        val screenId = prefs.getString(KEY_SCREEN_ID, null) ?: throw IOException("YouTube isn't linked yet")
        val json = postForm(
            "$baseUrl/pairing/get_lounge_token_batch",
            FormBody.Builder().add("screen_ids", screenId).build(),
            watch,
        ) ?: throw IOException("Couldn't refresh the YouTube link")
        val token = json.optJSONArray("screens")?.optJSONObject(0)?.optString("loungeToken")
            ?.takeIf { it.isNotBlank() } ?: throw IOException("YouTube returned an invalid TV link")
        synchronized(linkLock) {
            if (linkRevision.get() != revision || !isLinked) throw IOException("YouTube link changed")
            loungeToken = token
        }
        return token
    }

    private fun dropSession() {
        sid = null
        gsessionId = null
    }

    private fun postForm(url: String, body: FormBody, watch: Watch? = null): JSONObject? =
        response(Request.Builder().url(url).post(body).build(), watch) { response ->
            if (!response.isSuccessful) null else runCatching { JSONObject(response.body!!.string()) }.getOrNull()
        }

    private inline fun <T> response(request: Request, watch: Watch?, consume: (Response) -> T): T {
        val call = http.newCall(request)
        if (watch != null) {
            watch.call = call
            if (!watch.active) { call.cancel(); throw IOException("Playback watcher stopped") }
        }
        return try { call.execute().use(consume) }
        finally { if (watch != null && watch.call === call) watch.call = null }
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
