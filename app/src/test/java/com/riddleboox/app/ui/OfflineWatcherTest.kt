package com.riddleboox.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

// Robolectric 4.14 does not know this app's targetSdk (36). Unpinned, the
// sandbox it built had no Context.getSystemService(Class) (API 23+), so the
// watcher could not even be constructed; 35 is the newest it ships.
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class OfflineWatcherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun validated(): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    private fun unvalidated(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance()

    @Test
    fun `reports the answer only when it changes`() {
        val reports = mutableListOf<Boolean>()
        val watcher = OfflineWatcher(context) { reports += it }

        // Nothing validated yet: the first read-out is "offline".
        watcher.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = connectivity.activeNetwork ?: error("shadow has no active network")

        callback.onCapabilitiesChanged(network, validated())
        // Signal strength, metering — capabilities change on a network that
        // is exactly as online as before. The strip must not hear about it.
        callback.onCapabilitiesChanged(network, validated())
        callback.onLost(network)
        callback.onLost(network)

        assertEquals(listOf(true, false, true), reports)
    }

    @Test
    fun `an associated but unvalidated network still counts as offline`() {
        val reports = mutableListOf<Boolean>()
        val watcher = OfflineWatcher(context) { reports += it }
        watcher.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = connectivity.activeNetwork ?: error("shadow has no active network")

        callback.onCapabilitiesChanged(network, unvalidated())

        assertEquals(listOf(true), reports)
    }

    @Test
    fun `resuming re-reads the network and reports only a changed answer`() {
        val reports = mutableListOf<Boolean>()
        val watcher = OfflineWatcher(context) { reports += it }
        val network: Network = connectivity.activeNetwork ?: error("shadow has no active network")

        watcher.start()
        watcher.stop()
        // Still offline on return: nothing new to say.
        watcher.start()
        watcher.stop()
        // Came back to a validated network: one report, from the read-out.
        shadowOf(connectivity).setNetworkCapabilities(network, validated())
        watcher.start()

        assertEquals(listOf(true, false), reports)
    }
}
