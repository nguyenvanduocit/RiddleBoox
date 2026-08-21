package com.riddleboox.app.reply

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One request the fake endpoint received. */
class RecordedRequest(val path: String, val authorization: String?, val body: String)

/**
 * A minimal `/chat/completions` stand-in on a loopback socket: serves canned
 * responses in order and records what was posted. Every response closes its
 * connection, so a retry arrives on a fresh one.
 */
class FakeChatServer(private vararg val responses: String) : AutoCloseable {
    private val socket = ServerSocket(0)
    val requests = LinkedBlockingQueue<RecordedRequest>()
    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private val thread = Thread {
        for (response in responses) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return@Thread
            client.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                var length = 0
                var authorization: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val (name, value) = line.split(": ", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                    when (name.lowercase()) {
                        "content-length" -> length = value.trim().toInt()
                        "authorization" -> authorization = value
                    }
                }
                val body = CharArray(length).also { buf -> reader.read(buf, 0, length) }.concatToString()
                requests.add(RecordedRequest(path, authorization, body))
                it.getOutputStream().apply {
                    write(response.replace("\n", "\r\n").toByteArray())
                    flush()
                }
            }
        }
    }.apply { isDaemon = true; start() }

    fun takeRequest(): RecordedRequest =
        requests.poll(10, TimeUnit.SECONDS) ?: error("no request reached the fake endpoint")

    override fun close() {
        socket.close()
        thread.interrupt()
    }

    companion object {
        /**
         * One answered turn — reply, separator, transcript — split across SSE
         * chunks the way a real streamed reply arrives.
         */
        fun turn(transcript: String, reply: String): String =
            stream(*"$reply\n$TURN_SEPARATOR\n$transcript".chunked(7).toTypedArray())

        /** An SSE reply: each fragment becomes one `delta.content` chunk. */
        fun stream(vararg fragments: String): String = buildString {
            append("HTTP/1.1 200 OK\nContent-Type: text/event-stream\nConnection: close\n\n")
            for (f in fragments) {
                val escaped = f.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                append(
                    "data: {\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion.chunk\"," +
                        "\"created\":0,\"model\":\"fake-model\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$escaped\"}}]}\n\n",
                )
            }
            append("data: [DONE]\n\n")
        }

        /** An error reply with a JSON body. */
        fun error(code: Int, body: String): String =
            "HTTP/1.1 $code Error\nContent-Type: application/json\n" +
                "Content-Length: ${body.toByteArray().size}\nConnection: close\n\n$body"
    }
}
