package com.riddleboox.app.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.hairlineWidth
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock

/**
 * The one screen that stands between an opened diary and whoever is holding
 * it — a PIN prompt, and nothing past it until [PinStore.verify] says yes.
 *
 * Built as its own screen ahead of being wired anywhere: nothing in this app
 * navigates here yet (see `README.md`'s PIN-lock section and
 * `SettingsActivity.kt` near `togglePin()`). Reading [PinStore.isSet] on
 * `MainActivity`'s lifecycle and starting this screen for a result is a later
 * cycle's work — it changes when the diary can be opened at all, and that
 * deserves its own review rather than riding in on this one.
 *
 * A wrong PIN stays on this screen rather than finishing with a cancelled
 * result: the diary this guards has nowhere else to send a writer to try
 * again, so the retry loop lives here. [runningHead]'s default "‹ back"
 * still finishes the screen with no result set — that reads as
 * `RESULT_CANCELED`, which is fine today because nothing calls this screen
 * yet; whether back should be allowed at all is exactly the kind of decision
 * the later wiring cycle needs to make with the real caller in front of it.
 */
class LockActivity : Activity() {

    private lateinit var pinStore: PinStore
    private lateinit var pinField: EditText
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        pinStore = PinStore(this)

        pinField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 17f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, dp(8))
        }
        // Hidden until the first wrong guess: an empty warning row would sit
        // above the "unlock" caption on every visit, reading as if something
        // had already gone wrong before the writer typed anything.
        warning = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }

        val column = textBlock().apply {
            addView(
                LinearLayout(this@LockActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(32), 0, 0)
                    addView(caption("PIN"))
                    addView(
                        pinField,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        View(this@LockActivity).apply { setBackgroundColor(Color.BLACK) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairlineWidth()),
                    )
                },
            )
            addView(warning)
            addView(
                caption("unlock").apply {
                    textSize = 16f
                    setPadding(0, dp(32), 0, dp(12))
                    setOnClickListener { tryUnlock() }
                },
            )
        }
        setContentView(paperPage(runningHead("locked"), column))
    }

    private fun tryUnlock() {
        val pin = pinField.text.toString()
        if (pinStore.verify(pin)) {
            setResult(RESULT_OK)
            finish()
            return
        }
        warning.text = "wrong PIN"
        warning.visibility = View.VISIBLE
        pinField.text.clear()
        pinField.requestFocus()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LockActivity::class.java)
    }
}
