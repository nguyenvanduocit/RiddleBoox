package com.riddleboox.app.settings

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The address the pairing QR points at: a site-local IPv4 (what a home Wi-Fi
 * router hands out) rather than loopback or link-local, neither of which a
 * phone on the same network could ever reach.
 */
fun pickLanAddress(addresses: List<InetAddress>): Inet4Address? =
    addresses.filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }

/**
 * The shell [pickLanAddress] needs: the active network's own addresses, off
 * [ConnectivityManager] — `ACCESS_NETWORK_STATE` is already declared
 * (`AndroidManifest.xml`). Null with no active network, e.g. airplane mode.
 */
fun lanAddress(context: Context): Inet4Address? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return null
    val addresses = connectivityManager.getLinkProperties(network)?.linkAddresses?.map { it.address }
        ?: return null
    return pickLanAddress(addresses)
}
