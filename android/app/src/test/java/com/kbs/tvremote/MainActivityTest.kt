package com.kbs.tvremote

import android.content.Context
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.kbs.tvremote.ui.RemoteActions
import com.kbs.tvremote.ui.RemoteState
import kotlinx.coroutines.Job
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities
import okhttp3.WebSocket
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    private fun wifi(): ConnectivityManager {
        val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(10)
        shadowOf(network).setSocketFactory(SocketFactory.getDefault())
        shadow.addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.setNetworkCapabilities(network, capabilities)
        return manager
    }

    private fun state(activity: MainActivity): RemoteState = MainActivity::class.java
        .getDeclaredMethod("getUiState").apply { isAccessible = true }.invoke(activity) as RemoteState

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun tvServer(id: String, connections: AtomicInteger): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.getHeader("Upgrade") == "websocket" -> {
                    connections.incrementAndGet()
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"event":"ms.channel.connect","data":{}}""")
                        }
                    })
                }
                request.path == "/api/v2/" -> MockResponse().setBody("""{"device":{"OS":"Tizen","name":"TV","id":"$id","wifiMac":"AA:BB:CC:DD:EE:01"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        start(8001)
    }

    @Test fun automaticallyRecoversSavedIdentityToNewAddress() {
        val manager = wifi()
        val prefs = TvPrefs.of(RuntimeEnvironment.getApplication())
        prefs.edit().clear().putString(TvPrefs.IP, "127.0.0.2")
            .putString(TvPrefs.id("127.0.0.2"), "device-one")
            .putString(TvPrefs.token("127.0.0.2"), "saved-token")
            .putString(TvClient.pinKey("127.0.0.2"), "saved-pin")
            .putBoolean("legacy_127.0.0.2", true).commit()
        val connections = AtomicInteger()
        val server = tvServer("device-one", connections)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.discoverTvs = { listOf(TvDiscovery.FoundTv("127.0.0.1", "TV", "device-one", "AA:BB:CC:DD:EE:01")) }
        try {
            controller.start().resume()
            await { prefs.getString(TvPrefs.IP, null) == "127.0.0.1" && state(activity).status == com.kbs.tvremote.ui.Status.READY }
            assertEquals("saved-token", prefs.getString(TvPrefs.token("127.0.0.1"), null))
            assertEquals("saved-pin", prefs.getString(TvClient.pinKey("127.0.0.1"), null))
            assertEquals(1, connections.get())
            // A new Wi-Fi Network object must replace the socket even if the TV's address
            // remains the same; an existing socket is tied to the old Android network.
            val shadow = shadowOf(manager)
            val oldNetwork = TvNetwork(RuntimeEnvironment.getApplication()).current()!!
            val replacement = ShadowNetwork.newInstance(11)
            shadowOf(replacement).setSocketFactory(SocketFactory.getDefault())
            shadow.addNetwork(replacement, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true))
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadow.setNetworkCapabilities(replacement, capabilities)
            shadow.removeNetwork(oldNetwork)
            shadow.networkCallbacks.single().onCapabilitiesChanged(replacement, capabilities)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            await { connections.get() == 2 && state(activity).status == com.kbs.tvremote.ui.Status.READY }
            assertEquals("127.0.0.1", prefs.getString(TvPrefs.IP, null))
        } finally { controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); server.shutdown() }
    }

    @Test fun reusedAddressCannotPairWithAnotherTvOrReceiveOldCredentials() {
        val manager = wifi()
        val prefs = TvPrefs.of(RuntimeEnvironment.getApplication())
        prefs.edit().clear().putString(TvPrefs.IP, "127.0.0.1")
            .putString(TvPrefs.id("127.0.0.1"), "device-one")
            .putString(TvPrefs.token("127.0.0.1"), "saved-token").putBoolean("legacy_127.0.0.1", true).commit()
        val connections = AtomicInteger()
        val server = tvServer("device-two", connections)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        var scans = 0
        activity.discoverTvs = { scans++; listOf(TvDiscovery.FoundTv("127.0.0.1", "Same name", "device-two")) }
        try {
            controller.start().resume()
            await { scans == 1 && state(activity).status == com.kbs.tvremote.ui.Status.OFFLINE }
            assertEquals(0, connections.get())
            assertEquals("device-one", prefs.getString(TvPrefs.id("127.0.0.1"), null))
            assertEquals("saved-token", prefs.getString(TvPrefs.token("127.0.0.1"), null))
            assertEquals(1, shadowOf(manager).networkCallbacks.size)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
            server.shutdown()
        }
    }
    @Test fun choosingTvCancelsDiscoveryAndReplacesPreviousTvAppCache() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("tv", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(TvPrefs.IP, "127.0.0.2")
            .putBoolean("legacy_127.0.0.1", true).commit()
        TvApps.save(prefs, "127.0.0.2", listOf(TvShortcut(TvApps.TIZENBREW, "TizenBrew", TvApps.Kind.TIZENBREW)))
        TvApps.save(prefs, "127.0.0.1", listOf(TvShortcut(TvApps.YOUTUBE, "YouTube", TvApps.Kind.YOUTUBE)))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Upgrade") == "websocket") {
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                } else MockResponse().setBody("""{"device":{"name":"New TV"}}""")
        }
        server.start(8001)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            val pendingDiscovery = Job()
            field("discovery").set(activity, pendingDiscovery)
            val actions = field("actions").get(activity) as RemoteActions
            actions.saveIp("127.0.0.1")
            assertTrue(pendingDiscovery.isCancelled)
            assertEquals("127.0.0.1", prefs.getString(TvPrefs.IP, null))
            val installed = field("installedApps").get(activity) as List<*>
            assertEquals(listOf(TvShortcut(TvApps.YOUTUBE, "YouTube", TvApps.Kind.YOUTUBE)), installed)
            val getter = MainActivity::class.java.getDeclaredMethod("getUiState").apply { isAccessible = true }
            val state = getter.invoke(activity) as RemoteState
            assertEquals("127.0.0.1", state.ip)
            assertEquals(listOf("YouTube"), state.apps.map { it.name })
        } finally {
            val getter = MainActivity::class.java.getDeclaredMethod("getClient").apply { isAccessible = true }
            (getter.invoke(activity) as TvClient).disconnect()
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            server.shutdown()
        }
    }
}
