package com.riddleboox.app.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.security.SecureRandom

/**
 * "set up from phone" (in [SettingsActivity]) opens this: a QR pointing at a
 * tiny HTTP server this screen runs on the diary's own Wi-Fi ([PairingServer]),
 * so a phone can hand over an api key without anyone typing one on an e-ink
 * keyboard. The server only exists while this screen is on top — started in
 * [onResume], stopped in [onPause] — so the port this diary opens to the LAN
 * is only ever open while the writer is looking at this exact page.
 *
 * Hands the result back the same way [com.riddleboox.app.BookPickerActivity]
 * does: a [RESULT_OK] with the three fields as extras, never touching
 * [SettingsStore] itself — the screen that opened this one owns what the
 * choice means, and "save" still happens on that screen, the normal way.
 */
class PairActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var qrView: ImageView
    private var server: PairingServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        statusText = statusField()
        urlText = statusField().apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
        }
        qrView = ImageView(this)
        val column = textBlock().apply {
            addView(statusText)
            addView(
                qrView,
                LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(24) },
            )
            addView(urlText)
        }
        setContentView(paperPage(runningHead("set up from phone"), column))
    }

    /**
     * Starts fresh every time the screen comes to the front: a new random
     * token each visit, so a QR shown once cannot be scanned again later
     * after the writer has left this screen and come back.
     */
    override fun onResume() {
        super.onResume()
        val address = lanAddress(this)
        if (address == null) {
            statusText.text = "connect this diary to Wi-Fi first"
            urlText.text = ""
            qrView.visibility = View.GONE
            return
        }
        val token = randomToken()
        val pairing = PairingServer(token, ::onPaired)
        server = pairing
        val port = pairing.start()
        val url = "http://${address.hostAddress}:$port/?t=$token"
        statusText.text = "waiting for your phone…"
        urlText.text = url
        qrView.visibility = View.VISIBLE
        qrView.setImageBitmap(qrBitmap(url, dp(280)))
    }

    override fun onPause() {
        super.onPause()
        server?.stop()
        server = null
    }

    /** Called on [PairingServer]'s own thread once a phone submits a valid form. */
    private fun onPaired(payload: PairingPayload) {
        runOnUiThread {
            // A backgrounded screen the OS reclaimed still runs this
            // callback — same guard as SettingsActivity.pickModel().
            if (isFinishing || isDestroyed) return@runOnUiThread
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_BASE_URL, payload.baseUrl)
                    .putExtra(EXTRA_API_KEY, payload.apiKey)
                    .putExtra(EXTRA_MODEL, payload.model),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_BASE_URL = "com.riddleboox.app.settings.PAIR_BASE_URL"
        const val EXTRA_API_KEY = "com.riddleboox.app.settings.PAIR_API_KEY"
        const val EXTRA_MODEL = "com.riddleboox.app.settings.PAIR_MODEL"

        fun intent(context: Context): Intent = Intent(context, PairActivity::class.java)
    }
}

/** 128 bits, hex-encoded — fresh every time [PairActivity.onResume] runs. */
private fun randomToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
