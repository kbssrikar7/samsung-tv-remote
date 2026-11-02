package com.kbs.tvremote

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
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
import java.net.SocketTimeoutException
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
class TvClient(private val listener: Listener, private val pins: SharedPreferences, private val route: TvNetwork? = null) {

    enum class State { DISCONNECTED, CONNECTING, WAITING_FOR_ALLOW, CONNECTED }

    data class TvApp(val id: String, val name: String)

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onTokenIssued(token: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var host: String? = null

    @Volatile var state = State.DISCONNECTED
        private set

    private var retriedWithoutToken = false
    private var pairingAllowed = true
    private var triedSecure = false
    private var triedPlain = false
    private var plainAllowed = true
    private val stateRevision = java.util.concurrent.atomic.AtomicLong()
    private val pairingTimeout = Runnable {
        if (state == State.CONNECTING || state == State.WAITING_FOR_ALLOW) {
            socket?.cancel()
            socket = null
            setState(State.DISCONNECTED, PAIRING_TIMEOUT)
        }
    }

    /**
     * The TV serves a self-signed certificate on 8002, so CA validation can never pass. Instead the
     * certificate is pinned on first use per TV address and any later mismatch is rejected.
     */
    @SuppressLint("CustomX509TrustManager")
    private fun pinning(ip: String) = object : X509TrustManager {
        // Keep the original expectation even if recovery moves its preference key while
        // this handshake is in flight. A removed key must not turn this into first use.
        private val expectedPin = pins.getString(pinKey(ip), null)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            synchronized(certificateLock) { when (expectedPin ?: pins.getString(pinKey(ip), null)) {
                null -> pins.edit { putString(pinKey(ip), fingerprint) }
                fingerprint -> Unit
                else -> throw CertificateException("TV certificate changed - use Forget pairing if this is your TV")
            } }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private fun routedHttp(): OkHttpClient = route?.client(http) ?: http

    private fun socketHttp(ip: String): OkHttpClient {
        val trust = pinning(ip)
        return routedHttp().newBuilder().sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory,
            trust,
        )
        .hostnameVerifier { hostname, _ -> hostname == ip }
        .connectTimeout(5, TimeUnit.SECONDS)
        // The Allow prompt stays up for ~30s; keep the socket open while the user decides.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    }

    fun connect(ip: String, token: String?, allowPairing: Boolean = true, allowPlain: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { connect(ip, token, allowPairing, allowPlain) }
            return
        }
        if (ip != ip.trim()) { connect(ip.trim(), token, allowPairing, allowPlain); return }
        if (state != State.DISCONNECTED && host == ip) return
        disconnect()
        if (!TvPrefs.isValidIp(ip)) {
            setState(State.DISCONNECTED, "Invalid TV address. Use Find my TV in connection settings.")
            return
        }
        host = ip
        this.token = token
        pairingAllowed = allowPairing
        plainAllowed = allowPlain
        retriedWithoutToken = false
        triedSecure = false
        triedPlain = false
        if (!allowPairing && token.isNullOrEmpty() && !pins.getBoolean(legacyKey(ip), false)) {
            setState(State.DISCONNECTED, DENIED)
            return
        }
        setState(State.CONNECTING, null)
        open(legacy = allowPlain && pins.getBoolean(legacyKey(ip), false))
    }

    @Volatile private var token: String? = null
    @Volatile private var legacy = false

    /**
     * 2018+ TVs take the secure token channel on 8002; 2016-2017 models only offer the plain
     * channel on 8001, which is tried when 8002 refuses the connection.
     */
    private fun open(legacy: Boolean) {
        val ip = host ?: return
        if (!legacy && !pairingAllowed && token.isNullOrEmpty()) {
            setState(State.DISCONNECTED, DENIED)
            return
        }
        this.legacy = legacy
        if (legacy) triedPlain = true else triedSecure = true
        val name = Base64.encodeToString(CLIENT_NAME.toByteArray(), Base64.NO_WRAP)
        val url = okhttp3.HttpUrl.Builder()
            .scheme(if (legacy) "http" else "https")
            .host(ip).port(if (legacy) 8001 else 8002)
            .addPathSegments("api/v2/channels/samsung.remote.control")
            .addQueryParameter("name", name)
            .apply { if (!legacy && !token.isNullOrEmpty()) addQueryParameter("token", token) }
            .build()
        val transport = try { socketHttp(ip) } catch (error: IOException) {
            setState(State.DISCONNECTED, error.message)
            return
        }
        socket = transport.newWebSocket(Request.Builder().url(url).build(), SocketListener())
        main.removeCallbacks(pairingTimeout)
        main.postDelayed(pairingTimeout, 45_000)
    }

    fun disconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { disconnect() }
            return
        }
        main.removeCallbacks(pairingTimeout)
        val previous = socket
        socket = null
        previous?.cancel()
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

    /** Blocking. Report REST failures to the caller instead of silently ignoring them. */
    fun launchApp(app: TvApp, ip: String? = host) {
        if (ip == null) throw IOException("Choose a TV first")
        routedHttp().newCall(
            Request.Builder()
                .url("http://$ip:8001/api/v2/applications/${app.id}")
                .post(ByteArray(0).toRequestBody())
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't open ${app.name} on the TV (${response.code})")
        }
    }

    data class AppStatus(val name: String, val running: Boolean, val visible: Boolean)

    /**
     * Blocking. [app]'s name and whether it is running and in front, or null if it isn't installed
     * or the TV can't be reached.
     */
    fun appStatus(app: TvApp, ip: String? = host): AppStatus? = appStatus(app.id, ip)

    fun appStatus(appId: String, ip: String? = host): AppStatus? {
        if (ip == null) return null
        return runCatching {
            routedHttp().newCall(Request.Builder().url("http://$ip:8001/api/v2/applications/$appId").build()).execute().use {
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
    fun volume(ip: String? = host): Volume? {
        if (ip == null) return null
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
            routedHttp().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                Regex("<$tag>([^<]*)</$tag>").find(response.body!!.string())?.groupValues?.get(1)
            }
        }.getOrNull()
        val level = query("GetVolume", "CurrentVolume")?.trim()?.toIntOrNull() ?: return null
        if (level !in 0..100) return null
        val muted = when (query("GetMute", "CurrentMute")?.trim()) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return Volume(level, muted)
    }

    /** Blocking identity check, performed before reusing saved credentials at an address. */
    fun deviceInfo(ip: String, timeoutMs: Long = 3000): TvDiscovery.FoundTv? = runCatching {
        TvDiscovery.identify(ip, routedHttp().newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build())
    }.getOrNull()

    fun wake(mac: String): Boolean {
        val packet = magicPacket(mac) ?: return false
        val network = try { route?.requireNetwork() } catch (_: IOException) { return false }
        Thread {
            runCatching {
                DatagramSocket().use { s ->
                    if (network != null) route?.bind(s, network)
                    s.broadcast = true
                    val target = InetAddress.getByName("255.255.255.255")
                    repeat(3) { s.send(DatagramPacket(packet, packet.size, target, 9)) }
                }
            }
        }.start()
        return true
    }

    private fun send(json: JSONObject): Boolean {
        if (state != State.CONNECTED) return false
        val ws = socket ?: return false
        return ws.send(json.toString())
    }

    private fun setState(newState: State, detail: String?) {
        if (newState == State.CONNECTED || newState == State.DISCONNECTED) main.removeCallbacks(pairingTimeout)
        state = newState
        val revision = stateRevision.incrementAndGet()
        main.post { if (stateRevision.get() == revision) listener.onStateChanged(newState, detail) }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
            // With a valid token the connect event arrives immediately; otherwise the TV is
            // showing the Allow prompt.
            if (socket === webSocket && state == State.CONNECTING) setState(State.WAITING_FOR_ALLOW, null)
        } }

        override fun onMessage(webSocket: WebSocket, text: String) { main.post {
            if (socket !== webSocket) return@post
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return@post
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    host?.let { ip -> if (pins.getBoolean(legacyKey(ip), false) != legacy) pins.edit { putBoolean(legacyKey(ip), legacy) } }
                    msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
                        token = it
                        listener.onTokenIssued(it)
                    }
                    setState(State.CONNECTED, null)
                }
                "ms.channel.unauthorized" -> {
                    // A TV can invalidate a saved token after a reset or firmware update.
                    // Try pairing once without it, then stop so a denial never loops prompts.
                    if (pairingAllowed && !legacy && !token.isNullOrEmpty() && !retriedWithoutToken) {
                        retriedWithoutToken = true
                        token = null
                        host?.let { ip -> pins.edit { remove(TvPrefs.token(ip)) } }
                        socket = null
                        webSocket.cancel()
                        setState(State.CONNECTING, "Pairing again. Choose Allow on your TV.")
                        open(legacy = false)
                        return@post
                    }
                    socket = null
                    setState(State.DISCONNECTED, DENIED)
                    webSocket.close(1000, null)
                }
                "ms.channel.timeOut" -> {
                    socket = null
                    setState(State.DISCONNECTED, PAIRING_TIMEOUT)
                    webSocket.close(1000, null)
                }
                "ms.error" -> {
                    val message = msg.optJSONObject("data")?.optString("message").orEmpty()
                    if (message.contains("unrecognized method", ignoreCase = true) &&
                        message.contains("ms.remote.control", ignoreCase = true)) {
                        socket = null
                        setState(State.DISCONNECTED, UNSUPPORTED_REMOTE)
                        webSocket.close(1000, null)
                    }
                }
                // Logged so new firmware behaviour can be investigated; never contains the token.
                else -> Log.d(TAG, "event: ${msg.optString("event")}")
            }
        } }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post {
            if (socket === webSocket) {
                socket = null
                if (state != State.DISCONNECTED) setState(State.DISCONNECTED, "TV closed the connection. Try connecting again.")
            }
        } }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post {
            if (socket === webSocket) {
                socket = null
                if ((t is ConnectException || t is SocketTimeoutException) && state == State.CONNECTING) {
                    if (!legacy && !triedPlain && plainAllowed) { open(legacy = true); return@post }
                    if (legacy && !triedSecure) { open(legacy = false); return@post }
                }
                Log.d(TAG, "socket failure", t)
                val certificateChanged = generateSequence(t) { it.cause }.any { it is CertificateException }
                setState(State.DISCONNECTED, if (certificateChanged) CERTIFICATE_CHANGED else
                    "Can't reach your TV. Turn it on, check the home Wi-Fi, or tap Find my TV.")
            }
        } }
    }

    companion object {
        private val certificateLock = Any()

        internal fun magicPacket(mac: String): ByteArray? {
            if (!Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}").matches(mac)) return null
            val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
            return ByteArray(102) { index -> if (index < 6) 0xff.toByte() else bytes[(index - 6) % 6] }
        }
        /** The pairing token is bound to this name; changing it triggers a new Allow prompt. */
        const val CLIENT_NAME = "PhoneRemote"

        const val DENIED = "denied"
        const val PAIRING_TIMEOUT = "No reply from the TV. Turn it on and choose Allow using its remote, then try again."
        const val CERTIFICATE_CHANGED = "TV identity changed. If you reset this TV, use Forget TV pairing in Settings."
        const val UNSUPPORTED_REMOTE = "This TV's remote service does not support this app's commands. Some older TVs need a different pairing protocol."

        fun pinKey(ip: String) = "cert_$ip"
        private fun legacyKey(ip: String) = "legacy_$ip"
        private const val TAG = "TvRemote"
    }
}
