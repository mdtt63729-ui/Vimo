package com.atube.app.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * A cheap read on how usable the current connection is.
 *
 * Deliberately capability based: the link bandwidth estimate is already in the
 * system's network capabilities, so this costs nothing and can be asked on every
 * screen. A probe would add a request to every navigation, which is exactly the
 * kind of thing that makes an app feel slow.
 */
object NetworkQuality {

    enum class Level {
        /** No usable connection at all. */
        NONE,

        /** Connected, but the link estimate is very low. */
        LOW,

        GOOD
    }

    // Below these the UI should say something rather than silently spin.
    private const val LOW_DOWNSTREAM_KBPS = 600
    private const val LOW_UPSTREAM_KBPS = 200

    fun level(context: Context): Level {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return Level.NONE
        val network = connectivityManager.activeNetwork ?: return Level.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Level.NONE

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (!hasInternet && !isVpn) return Level.NONE

        // 0 means "unknown" on some devices, so only a real reading counts.
        val downstream = capabilities.linkDownstreamBandwidthKbps
        val upstream = capabilities.linkUpstreamBandwidthKbps
        if (downstream in 1 until LOW_DOWNSTREAM_KBPS) return Level.LOW
        if (upstream in 1 until LOW_UPSTREAM_KBPS) return Level.LOW

        return Level.GOOD
    }

    /** True only when the device really has no connection. */
    fun isOffline(context: Context): Boolean = !NetworkHelper.isNetworkAvailable(context)
}
