package com.riddleboox.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import com.riddleboox.app.ui.keepPageVisible

/**
 * The "PIN lock" row on [SettingsActivity]'s page, entirely
 * self-contained: unlike every other field there it writes through
 * [PinStore] the moment the writer confirms, not when "save" is tapped — see
 * [SettingsActivity.dirty], which deliberately never looks at this row. A
 * PIN just set is not an unsaved edit "Discard unsaved changes?" is
 * allowed to throw away.
 *
 * Only sets up the toggle for now; the lock screen that would read
 * [PinStore.verify] on app start is a later change, not wired here.
 */
internal class PinField(private val activity: Activity) {

    private val store = PinStore(activity)
    val field: TextView = activity.chooserField(label()) { toggle() }

    private fun label(): String = if (store.isSet()) "on" else "not set"

    /** Only two states matter to the tap: nothing set yet, or something to turn back off. */
    private fun toggle() {
        if (store.isSet()) confirmDisable() else promptSet()
    }

    private fun promptSet() {
        val input = pinInput()
        AlertDialog.Builder(activity)
            .setTitle("set a PIN")
            .setView(input)
            .setNegativeButton("cancel", null)
            .setPositiveButton("set") { _, _ ->
                val pin = input.text.toString()
                if (isValidPin(pin)) {
                    promptConfirm(pin)
                } else {
                    AlertDialog.Builder(activity)
                        .setMessage("The PIN needs at least 4 digits")
                        .setPositiveButton("ok", null)
                        .show()
                }
            }
            .show()
    }

    /**
     * A second, independent entry — not a review of the first — because
     * [InputType.TYPE_NUMBER_VARIATION_PASSWORD] masks every digit as it's
     * typed, so a writer who fat-fingers one digit in [promptSet] has no way
     * to see and catch it there. Only a matching re-entry sets the PIN; a
     * mismatch cancels outright rather than looping back into [promptSet],
     * leaving the writer to tap "set a PIN" again if they want another
     * attempt.
     */
    private fun promptConfirm(pin: String) {
        val confirmInput = pinInput()
        AlertDialog.Builder(activity)
            .setTitle("re-enter the PIN")
            .setView(confirmInput)
            .setNegativeButton("cancel", null)
            .setPositiveButton("confirm") { _, _ ->
                if (confirmInput.text.toString() == pin) {
                    store.set(pin)
                    field.text = label()
                } else {
                    AlertDialog.Builder(activity)
                        .setMessage("The two entries do not match")
                        .setPositiveButton("ok", null)
                        .show()
                }
            }
            .show()
    }

    private fun confirmDisable() {
        AlertDialog.Builder(activity)
            .setMessage("Turn off the PIN lock?")
            .setNegativeButton("cancel", null)
            .setPositiveButton("turn off") { _, _ ->
                store.clear()
                field.text = label()
            }
            .show()
    }

    private fun pinInput(): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        keepPageVisible()
    }

    /**
     * Validated here rather than left to [PinStore.set]: a store that can be
     * handed a 2-digit or empty PIN and silently accept it is how a writer
     * ends up locked out by a PIN they mistyped once and never actually set.
     */
    private fun isValidPin(pin: String): Boolean = pin.length >= 4 && pin.all { it.isDigit() }
}
