package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class LanAddressTest {

    @Test
    fun `a site-local IPv4 address is picked`() {
        val addr = InetAddress.getByName("192.168.1.42")
        assertEquals(addr, pickLanAddress(listOf(addr)))
    }

    @Test
    fun `loopback is skipped`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        assertNull(pickLanAddress(listOf(loopback)))
    }

    @Test
    fun `link-local is skipped`() {
        val linkLocal = InetAddress.getByName("169.254.1.5")
        assertNull(pickLanAddress(listOf(linkLocal)))
    }

    @Test
    fun `an IPv6 address is skipped even if the list has nothing else`() {
        val v6 = InetAddress.getByName("::1")
        assertNull(pickLanAddress(listOf(v6)))
    }

    @Test
    fun `the first usable address wins when several are offered`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val real = InetAddress.getByName("10.0.0.5")
        assertEquals(real, pickLanAddress(listOf(loopback, real)))
    }
}
