package com.riddleboox.app.settings

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/**
 * The HTTP server [PairActivity] opens on the LAN for exactly one phone to
 * pair with. [PairActivity] starts it in `onResume` and stops it in
 * `onPause`, so the port is only ever open while the QR is on the panel.
 *
 * [token] is checked on both routes — a value neither route ever leaks back
 * on a mismatch, since the response bodies for a bad token carry nothing.
 */
class PairingServer(private val token: String, private val onPaired: (PairingPayload) -> Unit) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts the server on a free port chosen by the OS and returns it. */
    fun start(): Int {
        val embedded = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                get("/") { serveForm(call) }
                post("/pair") { acceptSubmission(call) }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors() }.first().port
    }

    // Plain parameters rather than extension functions on the routing
    // lambda's receiver: `call` is passed in explicitly so these read as
    // ordinary suspend functions, nothing tied to ktor's DSL receiver rules.
    private suspend fun serveForm(call: ApplicationCall) {
        call.response.header("Cache-Control", "no-store")
        if (call.request.queryParameters["t"] != token) {
            call.respondText(text = "", status = HttpStatusCode.Forbidden)
            return
        }
        call.respondText(text = pairingPage(token, PROVIDERS), contentType = ContentType.Text.Html)
    }

    private suspend fun acceptSubmission(call: ApplicationCall) {
        call.response.header("Cache-Control", "no-store")
        val form = call.receiveParameters()
        if (form["t"] != token) {
            call.respondText(text = "", status = HttpStatusCode.Forbidden)
            return
        }
        when (val parsed = parsePairing(form.toPlainMap())) {
            is PairingParse.Rejected ->
                call.respondText(
                    text = rejectedPage(parsed.reason),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.BadRequest,
                )
            is PairingParse.Ok -> {
                call.respondText(text = confirmationPage(), contentType = ContentType.Text.Html)
                onPaired(parsed.payload)
                // Not calling stop() inline: this coroutine is running on the
                // very engine it would be asking to shut down, and stop() is
                // a blocking call — doing it here risks the engine waiting on
                // its own request handler. A short-lived daemon thread does
                // it a beat later instead, once this response has shipped.
                Thread { stop() }.apply { isDaemon = true; start() }
            }
        }
    }

    /** Blocking, with a short grace period. Safe to call more than once. */
    fun stop() {
        server?.stop(200, 500)
        server = null
    }

    private fun Parameters.toPlainMap(): Map<String, String> =
        names().associateWith { this[it].orEmpty() }
}
