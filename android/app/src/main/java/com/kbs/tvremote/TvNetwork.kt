package com.kbs.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Proxy

/** Bind only local TV sockets. Never change the app/process route used by YouTube. */
class TvNetwork(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var cachedNetwork: Network? = null
    private var cachedBase: OkHttpClient? = null
    private var cachedClient: OkHttpClient? = null

    fun current(excluding: Network? = null): Network? {
        val available = connectivity.allNetworks.filter { it != excluding }.mapNotNull { network ->
            connectivity.getNetworkCapabilities(network)?.let { network to it }
        }
        return select(available, connectivity.activeNetwork)
    }

    fun requireNetwork(): Network = current() ?: throw IOException(WIFI_REQUIRED)

    @Synchronized
    fun client(base: OkHttpClient, network: Network = requireNetwork()): OkHttpClient {
        if (cachedNetwork == network && cachedBase === base) return cachedClient!!
        cachedClient?.connectionPool?.evictAll()
        return base.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .proxy(Proxy.NO_PROXY)
            // A pooled socket from another network must never be reused after a Wi-Fi change.
            .connectionPool(ConnectionPool())
            .build().also { cachedNetwork = network; cachedBase = base; cachedClient = it }
    }

    fun bind(socket: DatagramSocket, network: Network = requireNetwork()) = network.bindSocket(socket)

    /** Listen only while the activity is resumed. Losing internet validation is not losing Wi-Fi. */
    fun observe(onChanged: () -> Unit): Closeable {
        var previous = current()
        var closed = false
        fun update(excluding: Network? = null) {
            if (closed) return
            val selected = current(excluding)
            if (selected != previous) { previous = selected; onChanged() }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            override fun onLost(network: Network) = update(excluding = network)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        return Closeable {
            if (!closed) { closed = true; connectivity.unregisterNetworkCallback(callback) }
        }
    }

    companion object {
        const val WIFI_REQUIRED = "Connect your phone to the same home Wi-Fi as your TV. Mobile data alone cannot reach it."

        internal fun select(available: List<Pair<Network, NetworkCapabilities>>, active: Network?): Network? {
            val local = available.filterNot { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET)) {
                val candidates = local.filter { it.second.hasTransport(transport) }
                candidates.firstOrNull { it.first == active }?.let { return it.first }
                candidates.firstOrNull()?.let { return it.first }
            }
            return null
        }
    }
}
