package com.kbs.tvremote.youtube

import androidx.core.content.edit
import android.content.SharedPreferences
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Plays videos on the TV's YouTube app through YouTube's "lounge" API - the same channel the
 * YouTube phone app uses after "Link with TV code". Works with TizenTube because it is the
 * YouTube TV web app. Ported from pyytlounge (https://github.com/FabioGNR/pyytlounge).
 *
 * Pairing yields a stable screenId (persisted); the short-lived lounge token and the bind
 * session are recreated on demand. All calls are blocking: use a background dispatcher.
 */
class LoungeClient(private val http: OkHttpClient, private val prefs: SharedPreferences) {

    private var loungeToken: String? = null
    private var sid: String? = null
    private var gsessionId: String? = null
    private var lastEventId = 0
    private var requestId = 0
    private var screenPresent = false

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

    /**
     * True when the TV's YouTube app has joined the lounge session. Checked through a fresh bind,
     * whose device list includes the TV ("LOUNGE_SCREEN") only while YouTube is really open; the
     * pairing API's "online" status stays true long after the app has closed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun isScreenConnected(): Boolean {
        dropSession()
        connect()
        return screenPresent
    }

    @Throws(IOException::class)
    fun playVideo(videoId: String) = command("setPlaylist", mapOf("videoId" to videoId))

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
            screenPresent = text.contains("LOUNGE_SCREEN")
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
    }
}
