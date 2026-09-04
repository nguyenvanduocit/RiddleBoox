package com.riddleboox.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A slim strip pinned to the bottom edge while the device has no internet,
 * with the one action that fixes it: opening the system Wi-Fi screen. Same
 * paper look as the onboarding overlays — white, black border, serif.
 *
 * Bottom rather than top because the chrome row already owns the top edge,
 * and covering its controls would trade one problem for another.
 */
fun offlineBanner(context: Context, onOpenWifiSettings: () -> Unit): View {
    val message = TextView(context).apply {
        text = "No internet connection"
        textSize = 18f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER_VERTICAL
    }
    val settingsButton = context.paperButton("wi-fi settings") { onOpenWifiSettings() }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setStroke(context.chromeStrokeWidth(), Color.BLACK)
            setColor(Color.WHITE)
        }
        // The strip must own its touches: a tap that misses the button should
        // not fall through and land as ink on the page underneath.
        isClickable = true
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        addView(message)
        addView(settingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = context.dp(24) })
    }
}

/**
 * Watches the default network and reports every change of the one fact the
 * banner cares about: does this device have working internet right now?
 *
 * "Working" means [NetworkCapabilities.NET_CAPABILITY_VALIDATED] — Android's
 * own probe has gotten a response through this network — so a Wi-Fi that is
 * associated but leads nowhere still counts as offline, which is exactly the
 * state a router with no uplink leaves the diary in.
 *
 * Callbacks are delivered on the main thread, so [onOfflineChanged] may touch
 * views directly.
 *
 * Only changes are reported. The system announces every capability change —
 * signal strength, metering — on a network that is exactly as online as it
 * was, and each report costs the listener an E-Ink repaint of the strip.
 */
class OfflineWatcher(context: Context, private val onOfflineChanged: (Boolean) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** The answer last reported; null until the first read-out. */
    private var offline: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            report(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            report(true)
        }
    }

    fun start() {
        // registerDefaultNetworkCallback stays silent when there is no network
        // at all, so the current state has to be read out once by hand.
        val current = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        report(current?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true)
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        connectivity.unregisterNetworkCallback(callback)
    }

    private fun report(nowOffline: Boolean) {
        if (nowOffline == offline) return
        offline = nowOffline
        // One line per real change: enough to tell, from logcat alone, whether
        // a strip that stays on screen was never told to go or was told and
        // never repainted.
        Log.i(TAG, if (nowOffline) "internet lost" else "internet reachable")
        onOfflineChanged(nowOffline)
    }

    private companion object {
        const val TAG = "OfflineWatcher"
    }
}
