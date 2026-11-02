package com.kbs.tvremote

import android.content.Context
import android.os.Looper
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TvClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TvClient
    private val states = CopyOnWriteArrayList<Pair<TvClient.State, String?>>()
    private val tokens = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("legacy_127.0.0.1", true).commit()
        server = MockWebServer().apply { start(8001) }
        client = TvClient(object : TvClient.Listener {
            override fun onStateChanged(state: TvClient.State, detail: String?) { states += state to detail }
            override fun onTokenIssued(token: String) { tokens += token }
        }, prefs)
    }

    @After fun teardown() {
        client.disconnect()
        server.shutdown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reply(vararg events: String) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                events.forEach { webSocket.send(it) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Expected callback; states=$states", condition())
    }

    @Test fun connectionRequiresSamsungAuthorizationAndDeliversToken() {
        reply("""{"event":"ms.channel.connect","data":{"token":"saved-token"}}""")
        client.connect("127.0.0.1", null)
        assertFalse(client.sendKey("KEY_HOME"))
        await { states.any { it.first == TvClient.State.CONNECTED } }
        assertEquals(listOf("saved-token"), tokens)
        assertTrue(client.sendKey("KEY_HOME"))
    }

    @Test fun deniedConnectionStopsAndCannotSendKeys() {
        reply("""{"event":"ms.channel.unauthorized"}""")
        client.connect("127.0.0.1", null)
        await { states.any { it.second == TvClient.DENIED } }
        assertEquals(TvClient.State.DISCONNECTED, client.state)
        assertFalse(client.sendKey("KEY_HOME"))
        assertEquals(1, server.requestCount)
    }

    @Test fun unsupportedRemoteCommandDoesNotLeaveTvMarkedConnected() {
        reply(
            """{"event":"ms.channel.connect","data":{}}""",
            """{"event":"ms.error","data":{"message":"unrecognized method value : ms.remote.control"}}""",
        )
        client.connect("127.0.0.1", null)
        await { states.any { it.second == TvClient.UNSUPPORTED_REMOTE } }
        assertEquals(TvClient.State.DISCONNECTED, client.state)
        assertFalse(client.sendKey("KEY_HOME"))
        assertEquals(1, server.requestCount)
    }

    @Test fun refusedSecurePortFallsBackToOlderTvChannel() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("legacy_127.0.0.1", false).commit()
        reply("""{"event":"ms.channel.connect","data":{}}""")
        client.connect("127.0.0.1", null)
        await { states.any { it.first == TvClient.State.CONNECTED } }
        assertTrue(prefs.getBoolean("legacy_127.0.0.1", false))
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredSecureTokenRetriesOnceWithoutToken() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("legacy_127.0.0.1", false)
            .putString(TvPrefs.token("127.0.0.1"), "expired").commit()
        val certificate = HeldCertificate.Builder().commonName("Samsung TV").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val secure = MockWebServer()
        secure.useHttps(tls.sslSocketFactory(), false)
        secure.start(8002)
        try {
            secure.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"event":"ms.channel.unauthorized"}""")
                }
            }))
            secure.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"event":"ms.channel.connect","data":{"token":"fresh"}}""")
                }
            }))
            client.connect("127.0.0.1", "expired")
            await { tokens.contains("fresh") }
            assertEquals(2, secure.requestCount)
            assertEquals("expired", secure.takeRequest().requestUrl!!.queryParameter("token"))
            assertNull(secure.takeRequest().requestUrl!!.queryParameter("token"))
            assertFalse(prefs.contains(TvPrefs.token("127.0.0.1")))
        } finally {
            client.disconnect()
            secure.shutdown()
        }
    }

    @Test fun silentTvTimesOutInsteadOfWaitingForever() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        client.connect("127.0.0.1", null)
        await { client.state == TvClient.State.WAITING_FOR_ALLOW }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        assertEquals(TvClient.State.DISCONNECTED, client.state)
        assertEquals(TvClient.PAIRING_TIMEOUT, states.last().second)
    }

    @Test fun changedCertificateStopsWithoutDowngradingToPlainSocket() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("legacy_127.0.0.1", false)
            .putString(TvClient.pinKey("127.0.0.1"), "different-certificate").commit()
        val tls = HandshakeCertificates.Builder()
            .heldCertificate(HeldCertificate.Builder().commonName("Samsung TV").build()).build()
        val secure = MockWebServer()
        secure.useHttps(tls.sslSocketFactory(), false)
        secure.start(8002)
        try {
            client.connect("127.0.0.1", null)
            await { states.any { it.second == TvClient.CERTIFICATE_CHANGED } }
            assertEquals(0, server.requestCount)
            assertFalse(client.sendKey("KEY_HOME"))
            assertEquals("different-certificate", prefs.getString(TvClient.pinKey("127.0.0.1"), null))
        } finally { secure.shutdown() }
    }

    @Test fun callbacksFromCancelledSocketCannotConnectNewSession() {
        reply("""{"event":"ms.channel.connect","data":{"token":"old"}}""")
        client.connect("127.0.0.1", null)
        client.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        assertEquals(TvClient.State.DISCONNECTED, client.state)
        assertTrue(tokens.isEmpty())
        assertFalse(states.any { it.first == TvClient.State.CONNECTED })
    }

    @Test fun manualAddressRejectsUrlsAndInvalidOctets() {
        assertTrue(TvPrefs.isValidIp(" 192.168.1.25 "))
        listOf("", "192.168.1.256", "192.168.1", "http://192.168.1.1", "1.2.3.4:8001", "0.0.0.0", "127.00.0.1")
            .forEach { assertFalse(it, TvPrefs.isValidIp(it)) }
    }

    @Test fun malformedStoredAddressFailsWithoutThrowingOrOpeningSocket() {
        client.connect("http://broken:8001", null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TvClient.State.DISCONNECTED, client.state)
        assertTrue(states.last().second!!.contains("Invalid TV address"))
        assertEquals(0, server.requestCount)
    }

    @Test fun backgroundControlDoesNotInitiateNewSecurePairing() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("legacy_127.0.0.1", false).commit()
        client.connect("127.0.0.1", null, allowPairing = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TvClient.DENIED, states.last().second)
        assertEquals(0, server.requestCount)
    }

    @Test fun unidentifiedAddressCannotFallBackToPlainEvenWhenPlainIsCached() {
        client.connect("127.0.0.1", "token", allowPlain = false)
        await { states.any { it.first == TvClient.State.DISCONNECTED } }
        assertEquals(0, server.requestCount)
    }

    @Test fun failedAppLaunchIsReportedToCaller() {
        reply("""{"event":"ms.channel.connect","data":{}}""")
        client.connect("127.0.0.1", null)
        await { client.state == TvClient.State.CONNECTED }
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(IOException::class.java) { client.launchApp(TvClient.TvApp("missing", "YouTube")) }
    }

    @Test fun failedVolumeResponseCannotBeDisplayedAsValidVolume() {
        val upnp = MockWebServer().apply { start(9197) }
        try {
            upnp.enqueue(MockResponse().setResponseCode(500).setBody("<CurrentVolume>42</CurrentVolume>"))
            assertNull(client.volume("127.0.0.1"))
            upnp.enqueue(MockResponse().setBody("<CurrentVolume>42</CurrentVolume>"))
            upnp.enqueue(MockResponse().setResponseCode(500))
            assertNull(client.volume("127.0.0.1"))
        } finally { upnp.shutdown() }
    }

    @Test fun obsoleteCachedPlainTransportCanRecoverToSecureChannel() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("legacy_127.0.0.2", true).commit()
        val tls = HandshakeCertificates.Builder()
            .heldCertificate(HeldCertificate.Builder().commonName("Samsung TV").build()).build()
        val secure = MockWebServer()
        secure.useHttps(tls.sslSocketFactory(), false)
        secure.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"event":"ms.channel.connect","data":{"token":"new"}}""")
            }
        }))
        secure.start(InetAddress.getByName("127.0.0.2"), 8002)
        try {
            client.connect("127.0.0.2", null)
            await { client.state == TvClient.State.CONNECTED }
            assertFalse(prefs.getBoolean("legacy_127.0.0.2", true))
            assertEquals(listOf("new"), tokens)
        } finally { client.disconnect(); secure.shutdown() }
    }

    @Test fun wakePacketRequiresExactlySixMacBytesAndRepeatsThemSixteenTimes() {
        listOf("", "AA:BB", "AA:BB:CC:DD:EE:GG", "AA:BB:CC:DD:EE:FF:11")
            .forEach { assertNull(TvClient.magicPacket(it)) }
        val packet = TvClient.magicPacket("AA:BB:CC:DD:EE:FF")!!
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        repeat(16) { i -> assertArrayEquals(packet.copyOfRange(6, 12), packet.copyOfRange(6 + i * 6, 12 + i * 6)) }
    }
}
