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
 * overlay để nơi gọi tự gỡ nó khỏi cây view. [asksForBooks] là việc chuỗi
 * giới thiệu có dừng lại hỏi quyền đọc sách hay không (MainActivity quyết
 * định cùng một chỗ với checkpoint) — dòng thân bài phải hứa đúng điều sẽ
 * xảy ra.
 */
fun welcomeOverlay(context: Context, asksForBooks: Boolean, onStart: (View) -> Unit): View {
    lateinit var overlay: View
    val title = TextView(context).apply {
        text = "Welcome"
        textSize = 40f
        typeface = Typeface.create("serif", Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
    }
    // Nói trước điều nút sẽ khởi động: thiếu dòng này, trang đầu tự hiện rồi
    // trang hai thay vào trông như một việc người viết lẽ ra phải làm. Không
    // hứa "không cần chạm gì" khi sau trang 5 sẽ có màn hình hỏi quyền.
    val body = TextView(context).apply {
        text = if (asksForBooks) {
            "Six short pages from me first. They turn on their own; I will stop once to ask about your books."
        } else {
            "Six short pages from me first. They turn on their own; you need not touch anything."
        }
        textSize = 18f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(context.dp(32), context.dp(16), context.dp(32), 0)
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
        addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(16) })
        addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(40) })
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
