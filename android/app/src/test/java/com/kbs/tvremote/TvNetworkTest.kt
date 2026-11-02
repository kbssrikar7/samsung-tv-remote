package com.kbs.tvremote

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.Proxy
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvNetworkTest {
    private fun caps(vararg transports: Int) = ShadowNetworkCapabilities.newInstance().apply {
        transports.forEach { shadowOf(this).addTransportType(it) }
    }

    @Test fun wifiWithoutInternetWinsOverCellularDefault() {
        val wifi = ShadowNetwork.newInstance(10)
        val cell = ShadowNetwork.newInstance(20)
        assertEquals(wifi, TvNetwork.select(listOf(cell to caps(NetworkCapabilities.TRANSPORT_CELLULAR),
            wifi to caps(NetworkCapabilities.TRANSPORT_WIFI)), cell))
    }

    @Test fun vpnWithWifiTransportIsNotTheLocalRoute() {
        val vpn = ShadowNetwork.newInstance(30)
        val wifi = ShadowNetwork.newInstance(10)
        assertEquals(wifi, TvNetwork.select(listOf(vpn to caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
            wifi to caps(NetworkCapabilities.TRANSPORT_WIFI)), vpn))
        assertNull(TvNetwork.select(listOf(vpn to caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)), vpn))
    }

    @Test fun mobileAloneIsNotATvNetworkAndEthernetIsAllowed() {
        val cell = ShadowNetwork.newInstance(20)
        val wired = ShadowNetwork.newInstance(40)
        assertNull(TvNetwork.select(listOf(cell to caps(NetworkCapabilities.TRANSPORT_CELLULAR)), cell))
        assertEquals(wired, TvNetwork.select(listOf(wired to caps(NetworkCapabilities.TRANSPORT_ETHERNET)), cell))
    }

    @Test fun activeWifiIsPreferredWhenSeveralWifiNetworksExist() {
        val first = ShadowNetwork.newInstance(10)
        val second = ShadowNetwork.newInstance(11)
        assertEquals(second, TvNetwork.select(listOf(first to caps(NetworkCapabilities.TRANSPORT_WIFI),
            second to caps(NetworkCapabilities.TRANSPORT_WIFI)), second))
    }

    @Test fun routingUsesNetworkFactoryAndSeparatePoolWithoutBindingWholeProcess() {
        val context = RuntimeEnvironment.getApplication()
        val route = TvNetwork(context)
        val first = ShadowNetwork.newInstance(10)
        val second = ShadowNetwork.newInstance(11)
        val factory = SocketFactory.getDefault()
        shadowOf(first).setSocketFactory(factory)
        shadowOf(second).setSocketFactory(factory)
        val internet = OkHttpClient()
        val local = route.client(internet, first)
        assertSame(factory, local.socketFactory)
        assertSame(local, route.client(internet, first))
        assertEquals(Proxy.NO_PROXY, local.proxy)
        val changed = route.client(internet, second)
        assertNotSame(local.connectionPool, changed.connectionPool)
        assertNotSame(internet.connectionPool, local.connectionPool)
        assertNull(context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
        DatagramSocket().use { socket ->
            route.bind(socket, first)
            assertTrue(shadowOf(first).isSocketBound(socket))
        }
    }

    @Test fun observersUnregisterOnCloseAndDoNotReconnectForUnchangedNetwork() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val route = TvNetwork(context)
        var changes = 0
        val subscription = route.observe { changes++ }
        val callback = shadow.networkCallbacks.single()
        val wifi = route.current()!!
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(0, changes)
        shadow.clearAllNetworks()
        callback.onLost(wifi)
        assertEquals(1, changes)
        subscription.close()
        subscription.close()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
