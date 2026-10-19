package com.app.market.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class AndroidNetworkMetering(context: Context) : NetworkMetering {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean =
        connectivity?.let { it.activeNetwork != null && it.isActiveNetworkMetered } ?: false

    override val changes = callbackFlow {
        val manager = connectivity
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isMetered()) }
            override fun onLost(network: Network) { trySend(isMetered()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(isMetered()) }
        }
        manager?.registerDefaultNetworkCallback(callback)
        trySend(isMetered())
        awaitClose { manager?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
