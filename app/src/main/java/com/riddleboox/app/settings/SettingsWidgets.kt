package com.riddleboox.app.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.keepPageVisible

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

/**
 * A [sectionHeader] carrying one small action at its right edge — for an
 * operation that belongs to the whole section rather than to any one field
 * under it. The action is set in [caption], the same quiet chrome type as the
 * running head's "save", so it reads as a control and not as a fourth field.
 */
internal fun Activity.sectionHeader(title: String, actionLabel: String, onAction: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(
            sectionHeader(title),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            // The padding is the tap target, the same trade the running head
            // makes: a 16sp caption is a small thing to hit with a stylus.
            caption(actionLabel).apply {
                setPadding(dp(24), dp(16), 0, dp(4))
                setOnClickListener { onAction() }
            },
        )
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
    keepPageVisible()
    textSize = 17f
    typeface = Typeface.MONOSPACE
    setTextColor(Color.BLACK)
    setBackgroundColor(Color.TRANSPARENT)
    setPadding(0, dp(8), 0, dp(8))
}
