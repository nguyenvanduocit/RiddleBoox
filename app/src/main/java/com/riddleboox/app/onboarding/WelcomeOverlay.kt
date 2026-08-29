package com.riddleboox.app.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp

/**
 * Toàn màn hình, đè lên mọi thứ khác — trang giấy và bút bên dưới đã bị khoá
 * (xem MainActivity), đây chỉ là thứ khiến điều đó hiện ra trước khi có chữ
 * nào được viết. [onStart] gọi đúng một lần, từ cú chạm nút, nhận lại chính
 * overlay để nơi gọi tự gỡ nó khỏi cây view.
 */
fun welcomeOverlay(context: Context, onStart: (View) -> Unit): View {
    lateinit var overlay: View
    val title = TextView(context).apply {
        text = "Welcome"
        textSize = 40f
        typeface = Typeface.create("serif", Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
    }
    val startButton = TextView(context).apply {
        text = "begin"
        textSize = 20f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(context.dp(48), context.dp(16), context.dp(48), context.dp(16))
        background = GradientDrawable().apply {
            setStroke(context.dp(2), Color.BLACK)
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener { onStart(overlay) }
    }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(title)
        addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(48) })
    }
    overlay = FrameLayout(context).apply {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
    }
    return overlay
}
