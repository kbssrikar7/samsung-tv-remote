package com.kbs.tvremote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Finds Samsung TVs on the local network.
 *
 * Tizen TVs answer SSDP searches for the DIAL service (they ignore the Samsung-specific
 * RemoteControlReceiver search target), so DIAL responders are collected first and then
 * confirmed as Samsung TVs through the remote API's info endpoint on port 8001.
 */
object TvDiscovery {

    data class FoundTv(val ip: String, val name: String)

    private const val SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1"
    private const val LISTEN_MS = 3000

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** Blocking (about 3 s); call from a background dispatcher. */
    fun discover(): List<FoundTv> = candidateIps().mapNotNull(::identify)

    private fun candidateIps(): Set<String> {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $SEARCH_TARGET\r\n\r\n"
            ).toByteArray()
        val ips = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            // UDP is lossy; a second search catches TVs that missed the first.
            repeat(2) { socket.send(DatagramPacket(request, request.size, group, 1900)) }
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + LISTEN_MS
            while (System.currentTimeMillis() < deadline) {
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

    private fun identify(ip: String): FoundTv? = runCatching {
        http.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val device = JSONObject(response.body!!.string()).getJSONObject("device")
            if (device.optString("OS") != "Tizen") return null
            FoundTv(ip, device.optString("name").ifEmpty { "Samsung TV" })
        }
    }.getOrNull()
}
