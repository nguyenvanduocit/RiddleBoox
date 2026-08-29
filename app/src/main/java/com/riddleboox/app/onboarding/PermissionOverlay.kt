package com.riddleboox.app.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp

/**
 * Shown once, mid-onboarding, right after the diary explains it can read the
 * writer's books — the natural place to ask for the permission that makes
 * that true. Same full-screen paper look as [welcomeOverlay], with two ways
 * off it: [onAllow] sends the writer to the OS switch, [onSkip] leaves it for
 * later (see the "books on this device" row in Settings).
 */
fun permissionOverlay(context: Context, onAllow: () -> Unit, onSkip: () -> Unit): android.view.View {
    val title = TextView(context).apply {
        text = "One more thing"
        textSize = 32f
        typeface = Typeface.create("serif", Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
    }
    val body = TextView(context).apply {
        text = "To read the words inside your books, I need permission to see files on this device."
        textSize = 18f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(context.dp(32), context.dp(16), context.dp(32), 0)
    }
    fun button(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 20f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(context.dp(48), context.dp(16), context.dp(48), context.dp(16))
        background = GradientDrawable().apply {
            setStroke(context.dp(2), Color.BLACK)
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener { onTap() }
    }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(title)
        addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(16) })
        addView(button("allow", onAllow), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(40) })
        addView(button("not now", onSkip), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(16) })
    }
    return FrameLayout(context).apply {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
    }
}
