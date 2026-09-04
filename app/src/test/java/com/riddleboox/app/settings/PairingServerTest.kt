package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun get(port: Int, path: String): HttpURLConnection =
    (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply { connect() }

private fun post(port: Int, path: String, body: String): HttpURLConnection {
    val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.outputStream.use { it.write(body.toByteArray()) }
    connection.connect()
    return connection
}

class PairingServerTest {

    @Test
    fun `GET with the right token serves the pairing page`() {
        val server = PairingServer("secret") {}
        val port = server.start()
        try {
            val connection = get(port, "/?t=secret")
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            assertTrue2(connection.inputStream.bufferedReader().readText().contains("send to my diary"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `GET with the wrong token is refused`() {
        val server = PairingServer("secret") {}
        val port = server.start()
        try {
            val connection = get(port, "/?t=wrong")
            assertEquals(403, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a valid POST calls back with the parsed payload and then stops itself`() {
        val latch = CountDownLatch(1)
        var received: PairingPayload? = null
        val server = PairingServer("secret") { received = it; latch.countDown() }
        val port = server.start()
        val connection = post(
            port,
            "/pair",
            "t=secret&provider=OpenAI&api_key=sk-mine&model=",
        )
        assertEquals(200, connection.responseCode)
        assertTrue2(latch.await(2, TimeUnit.SECONDS))
        assertEquals(PairingPayload("https://api.openai.com", "sk-mine", ""), received)

        // The server already stopped itself after the valid POST — a second
        // request must not find anything listening.
        Thread.sleep(300)
        var refused = false
        try {
            get(port, "/?t=secret").responseCode
        } catch (e: java.io.IOException) {
            refused = true
        }
        assertTrue2(refused)
    }

    @Test
    fun `a POST with the wrong token is refused and never calls back`() {
        var called = false
        val server = PairingServer("secret") { called = true }
        val port = server.start()
        try {
            val connection = post(port, "/pair", "t=wrong&provider=OpenAI&api_key=sk-mine")
            assertEquals(403, connection.responseCode)
            Thread.sleep(100)
            assertNull(if (called) "called" else null)
        } finally {
            server.stop()
        }
    }
}

private fun assertTrue2(condition: Boolean) {
    org.junit.Assert.assertTrue(condition)
}
