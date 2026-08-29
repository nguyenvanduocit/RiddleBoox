package com.riddleboox.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.widget.TextView

/**
 * One settings row whose value is a preset picked from a short list — every
 * enum-backed setting on [SettingsActivity]'s page (reply font size,
 * send mode) is the same shape: read a store, remember what was
 * loaded against what the writer chose, and turn a tap into a single-choice
 * dialog. One class here replaces one copy of that shape per setting, so
 * adding the next one costs a single constructor call, not a new pick
 * function plus a new line in `dirty()` and `writeAndFinish()`.
 *
 * [read]/[write] rather than a shared store interface: the stores this wraps
 * ([ReplyFontSizeStore], [SendModeStore]) already
 * agree on the shape without needing to say so in a type, and a marker
 * interface across three unrelated files would exist only for this.
 */
internal class EnumSettingRow<T : Any>(
    private val activity: Activity,
    private val entries: List<T>,
    private val labelOf: (T) -> String,
    private val dialogTitle: String,
    private val read: () -> T,
    private val write: (T) -> Unit,
) {
    private val loaded: T = read()
    private var chosen: T = loaded

    val field: TextView = activity.chooserField(labelOf(loaded)) { pick() }

    /** Compared by value, not identity: what [SettingsActivity.leave] asks "Discard unsaved changes?" about. */
    val dirty: Boolean get() = chosen != loaded

    fun save() = write(chosen)

    private fun pick() {
        val labels = entries.map(labelOf).toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setSingleChoiceItems(labels, entries.indexOf(chosen)) { dialog, which ->
                chosen = entries[which]
                field.text = labelOf(chosen)
                dialog.dismiss()
            }
            .show()
    }
}
