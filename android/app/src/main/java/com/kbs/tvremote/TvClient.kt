package com.kbs.tvremote

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Samsung Tizen (2016+) local remote protocol.
 *
 *  keys/text/apps : wss://TV:8002/api/v2/channels/samsung.remote.control?name=<b64>&token=<t>
 *  device info    : GET  http://TV:8001/api/v2/
 *  app launch     : POST http://TV:8001/api/v2/applications/<appId>
 *  power on       : Wake-on-LAN to the TV's MAC
 *
 * The first connection without a token makes the TV show an Allow prompt; the reply then carries
 * a token bound to CLIENT_NAME, which must be reused for later connections.
 */
class TvClient(private val listener: Listener, private val pins: SharedPreferences) {

    enum class State { DISCONNECTED, CONNECTING, WAITING_FOR_ALLOW, CONNECTED }

    data class TvApp(val id: String, val name: String)

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onTokenIssued(token: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    @Volatile private var host: String? = null

    var state = State.DISCONNECTED
        private set

    /**
     * The TV serves a self-signed certificate on 8002, so CA validation can never pass. Instead the
     * certificate is pinned on first use per TV address and any later mismatch is rejected.
     */
    @SuppressLint("CustomX509TrustManager")
    private val pinning = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val ip = host ?: throw CertificateException("No TV selected")
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            when (pins.getString(pinKey(ip), null)) {
                null -> pins.edit { putString(pinKey(ip), fingerprint) }
                fingerprint -> Unit
                else -> throw CertificateException("TV certificate changed - use Forget pairing if this is your TV")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinning), null) }.socketFactory,
            pinning,
        )
        .hostnameVerifier { hostname, _ -> hostname == host }
        .connectTimeout(5, TimeUnit.SECONDS)
        // The Allow prompt stays up for ~30s; keep the socket open while the user decides.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(ip: String, token: String?) {
        if (state != State.DISCONNECTED && host == ip) return
        disconnect()
        host = ip
        this.token = token
        setState(State.CONNECTING, null)
        open(legacy = pins.getBoolean(legacyKey(ip), false))
    }

    @Volatile private var token: String? = null
    @Volatile private var legacy = false

    /**
     * 2018+ TVs take the secure token channel on 8002; 2016-2017 models only offer the plain
     * channel on 8001, which is tried when 8002 refuses the connection.
     */
    private fun open(legacy: Boolean) {
        val ip = host ?: return
        this.legacy = legacy
        val name = Base64.encodeToString(CLIENT_NAME.toByteArray(), Base64.NO_WRAP)
        var url = if (legacy) {
            "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$name"
        } else {
            "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        }
        if (!legacy && !token.isNullOrEmpty()) url += "&token=$token"
        socket = http.newWebSocket(Request.Builder().url(url).build(), SocketListener())
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null)
    }

    fun sendKey(key: String) = send(
        JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params", JSONObject()
                    .put("Cmd", "Click")
                    .put("DataOfCmd", key)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
    )

    /** The WebSocket ed.apps.launch event is ignored by this firmware; the REST endpoint works. */
    fun launchApp(app: TvApp) {
        val ip = host ?: return
        http.newCall(
            Request.Builder()
                .url("http://$ip:8001/api/v2/applications/${app.id}")
                .post(ByteArray(0).toRequestBody())
                .build()
        ).enqueue(IgnoreResponse)
    }

    data class AppStatus(val name: String, val running: Boolean, val visible: Boolean)

    /**
     * Blocking. [app]'s name and whether it is running and in front, or null if it isn't installed
     * or the TV can't be reached.
     */
    fun appStatus(app: TvApp): AppStatus? = appStatus(app.id)

    fun appStatus(appId: String): AppStatus? {
        val ip = host ?: return null
        return runCatching {
            http.newCall(Request.Builder().url("http://$ip:8001/api/v2/applications/$appId").build()).execute().use {
                if (!it.isSuccessful) return null
                val json = JSONObject(it.body!!.string())
                AppStatus(json.optString("name"), json.optBoolean("running"), json.optBoolean("visible"))
            }
        }.getOrNull()
    }

    data class Volume(val level: Int, val muted: Boolean)

    /**
     * Blocking. The TV's current volume through its UPnP RenderingControl service (the key-based
     * remote API can only step the volume, not read it). Null if the TV doesn't answer.
     */
    fun volume(): Volume? {
        val ip = host ?: return null
        fun query(action: String, tag: String): String? = runCatching {
            val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
                """<u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">""" +
                """<InstanceID>0</InstanceID><Channel>Master</Channel></u:$action></s:Body></s:Envelope>"""
            val request = Request.Builder()
                .url("http://$ip:9197/upnp/control/RenderingControl1")
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#$action\"")
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                Regex("<$tag>([^<]*)</$tag>").find(response.body!!.string())?.groupValues?.get(1)
            }
        }.getOrNull()
        val level = query("GetVolume", "CurrentVolume")?.trim()?.toIntOrNull() ?: return null
        val muted = query("GetMute", "CurrentMute")?.trim() == "1"
        return Volume(level, muted)
    }

    /** Reads the TV's name and Wi-Fi MAC (the MAC lets Wake-on-LAN work later while the TV is off). */
    fun fetchInfo(ip: String, onInfo: (name: String, mac: String) -> Unit) {
        http.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val device = runCatching { JSONObject(it.body!!.string()).getJSONObject("device") }.getOrNull()
                        ?: return
                    val name = device.optString("name")
                    val mac = device.optString("wifiMac")
                    main.post { onInfo(name, mac) }
                }
            }
        })
    }

    fun wake(mac: String) {
        Thread {
            runCatching {
                val macBytes = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
                val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { macBytes.asList() }
                DatagramSocket().use { s ->
                    s.broadcast = true
                    val target = InetAddress.getByName("255.255.255.255")
                    repeat(3) { s.send(DatagramPacket(packet, packet.size, target, 9)) }
                }
            }
        }.start()
    }

    private fun send(json: JSONObject): Boolean {
        val ws = socket ?: return false
        return ws.send(json.toString())
    }

    private fun setState(newState: State, detail: String?) {
        state = newState
        main.post { listener.onStateChanged(newState, detail) }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // With a valid token the connect event arrives immediately; otherwise the TV is
            // showing the Allow prompt.
            if (socket === webSocket && state == State.CONNECTING) setState(State.WAITING_FOR_ALLOW, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    host?.let { ip -> if (pins.getBoolean(legacyKey(ip), false) != legacy) pins.edit { putBoolean(legacyKey(ip), legacy) } }
                    msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
                        main.post { listener.onTokenIssued(it) }
                    }
                    setState(State.CONNECTED, null)
                }
                "ms.channel.unauthorized" -> {
                    setState(State.DISCONNECTED, DENIED)
                    webSocket.close(1000, null)
                }
                "ms.channel.timeOut" -> {
                    setState(State.DISCONNECTED, "Allow prompt timed out")
                    webSocket.close(1000, null)
                }
                // Logged so new firmware behaviour can be investigated; never contains the token.
                else -> Log.d(TAG, "event: $text")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) {
                socket = null
                if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) {
                socket = null
                if (!legacy && t is ConnectException && state == State.CONNECTING) {
                    open(legacy = true)
                    return
                }
                Log.d(TAG, "socket failure", t)
                setState(State.DISCONNECTED, t.message ?: "Connection failed")
            }
        }
    }

    private object IgnoreResponse : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) = response.close()
    }

    companion object {
        /** The pairing token is bound to this name; changing it triggers a new Allow prompt. */
        const val CLIENT_NAME = "PhoneRemote"

        const val DENIED = "denied"

        fun pinKey(ip: String) = "cert_$ip"
        private fun legacyKey(ip: String) = "legacy_$ip"
        private const val TAG = "TvRemote"
    }
}
