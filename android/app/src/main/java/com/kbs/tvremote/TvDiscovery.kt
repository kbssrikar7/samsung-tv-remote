package com.kbs.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Callable

/**
 * Finds Samsung TVs on the local network.
 *
 * Tizen TVs answer SSDP searches for the DIAL service (they ignore the Samsung-specific
 * RemoteControlReceiver search target), so DIAL responders are collected first and then
 * confirmed as Samsung TVs through the remote API's info endpoint on port 8001.
 */
object TvDiscovery {

    data class FoundTv(val ip: String, val name: String, val deviceId: String? = null, val mac: String? = null) {
        val identity: TvIdentity get() = TvIdentity.from(deviceId, mac)
    }

    private val SEARCH_TARGETS = listOf(
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "ssdp:all",
    )
    private const val LISTEN_MS = 3000

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Blocking (about 3 s); call from a background dispatcher. */
    fun discover(context: Context, route: TvNetwork = TvNetwork(context)): List<FoundTv> {
        val network = route.requireNetwork()
        val routedHttp = route.client(http, network)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("tv-discovery")?.apply { setReferenceCounted(false); acquire() }
        try {
            // Check responders concurrently so a printer or another TV cannot delay setup by
            // several seconds per device. Never scan arbitrary hosts on the home network.
            val candidates = candidateIps { route.bind(it, network) }.take(64)
            val pool = Executors.newFixedThreadPool(8)
            return try {
                pool.invokeAll(candidates.map { ip -> Callable { identify(ip, routedHttp) } }, 12, TimeUnit.SECONDS)
                    .mapNotNull { if (it.isCancelled) null else runCatching { it.get() }.getOrNull() }
                    .sortedBy { it.name }
            } finally { pool.shutdownNow() }
        } finally { if (lock?.isHeld == true) lock.release() }
    }

    private fun candidateIps(bind: (DatagramSocket) -> Unit): Set<String> {
        fun request(target: String) = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $target\r\n\r\n"
            ).toByteArray()
        val ips = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            bind(socket)
            socket.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            // UDP is lossy; a second search catches TVs that missed the first.
            repeat(2) { SEARCH_TARGETS.forEach { target ->
                val bytes = request(target)
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            } }
            val buffer = ByteArray(2048)
            val deadline = android.os.SystemClock.elapsedRealtime() + LISTEN_MS
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    ips += packet.address.hostAddress ?: continue
                } catch (_: SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
        }
        return ips
    }

    internal fun identify(ip: String, client: OkHttpClient): FoundTv? = runCatching {
        client.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            parse(ip, JSONObject(response.body!!.string()))
        }
    }.getOrNull()

    internal fun parse(ip: String, info: JSONObject): FoundTv? {
        val device = info.optJSONObject("device") ?: return null
        if (!device.optString("OS").equals("Tizen", ignoreCase = true)) return null
        val id = listOf("id", "duid", "udn").map { device.optString(it) }
            .plus(info.optString("id")).firstOrNull { TvIdentity.from(it, null).known }
        return FoundTv(ip, device.optString("name").ifEmpty { "Samsung TV" }, id,
            device.optString("wifiMac").takeIf { it.isNotBlank() })
    }
}
