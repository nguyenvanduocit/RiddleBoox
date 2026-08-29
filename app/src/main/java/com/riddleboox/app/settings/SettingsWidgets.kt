package com.riddleboox.app.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp

/**
 * The small set of row widgets [SettingsActivity]'s own page is built from —
 * shared with [EnumSettingRow] and [PinField] so a picker or a toggle reads
 * exactly like the plain text fields around it.
 */

/** A label above its input, closed by the hairline the text sits on. */
internal fun Activity.field(label: String, input: View): LinearLayout {
    val activity = this
    return LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(32), 0, 0)
        addView(caption(label))
        addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            View(activity).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )
    }
}

/**
 * A line of prose where the other fields hold a value: it wraps, and it is
 * set in the page's own face rather than the monospace the other three use,
 * because nobody proofreads it for a mistyped character.
 */
internal fun Activity.statusField(): TextView = TextView(this).apply {
    textSize = 18f
    setTextColor(Color.BLACK)
    setPadding(0, dp(8), 0, dp(8))
    setLineSpacing(dp(3).toFloat(), 1f)
}

/**
 * A page break between clusters of related settings (connection, reading &
 * writing, security & info) — bigger and heavier than [caption], the field-label
 * type, so a glance down the page reads section boundaries before it reads
 * any one field's name. E-ink has no colour to lean on for this, only size,
 * weight and space.
 */
internal fun Activity.sectionHeader(title: String): TextView = TextView(this).apply {
    text = title
    textSize = 22f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    letterSpacing = 0.08f
    isAllCaps = true
    setTextColor(Color.BLACK)
    setPadding(0, dp(56), 0, dp(4))
}

/** Reads like the fields around it, but opens a list instead of a keyboard. */
internal fun Activity.chooserField(value: String, onTap: () -> Unit): TextView = TextView(this).apply {
    text = value
    setSingleLine()
    textSize = 17f
    typeface = Typeface.MONOSPACE
    setTextColor(Color.BLACK)
    setPadding(0, dp(8), 0, dp(8))
    setOnClickListener { onTap() }
}

/**
 * Monospace, because these are the three strings a typo silently turns into
 * a 401: an `l` has to look unlike a `1` while proofreading a pasted key.
 */
internal fun Activity.valueField(value: String, inputType: Int): EditText = EditText(this).apply {
    setText(value)
    setSingleLine()
    this.inputType = inputType
    textSize = 17f
    typeface = Typeface.MONOSPACE
    setTextColor(Color.BLACK)
    setBackgroundColor(Color.TRANSPARENT)
    setPadding(0, dp(8), 0, dp(8))
}
