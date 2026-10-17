package com.app.market.data.download

import android.content.Context
import android.net.ConnectivityManager

internal class AndroidNetworkMetering(context: Context) : NetworkMetering {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean =
        connectivity?.let { it.activeNetwork != null && it.isActiveNetworkMetered } ?: false
}
