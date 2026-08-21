package com.riddleboox.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The sheet of paper every screen in this app is a page of: white edge to edge,
 * black ink, and the same quiet caption type as its only chrome.
 *
 * It lives in one file because it drifted once. The diary page kept the whole
 * [openPaperWindow] flag set while the three screens reached from it kept only
 * half of it, and spent their life with a 50px band of system grey across the
 * top and a running head sitting 136px lower than the label that opened them.
 */

/**
 * Takes the system bars away *and* the space they held.
 *
 * FULLSCREEN/HIDE_NAVIGATION hide the bars; the LAYOUT_ pair is what lets the
 * page occupy where they used to be. Without them the bars vanish and their gap
 * stays — on a page that starts at the very top that reads as a band of wasted
 * paper, and the window still paints the status bar's grey scrim into it.
 */
fun Activity.openPaperWindow() {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

/**
 * The quiet caption type this app labels everything with.
 *
 * Black at full opacity, never faded: an alpha-faded caption reads as "quiet"
 * on a normal display, but e-ink's coarser grayscale dithers a faded black down
 * to something that isn't reliably legible at 16sp. The restraint comes from
 * the small size and the serif caps instead.
 */
fun Context.caption(label: String): TextView = TextView(this).apply {
    text = label
    textSize = 16f
    // UI chrome is sans-serif medium for reliable glyph shapes on BOOX's
    // grayscale panel; long-form page content keeps the serif face below.
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.08f
    isAllCaps = true
    setTextColor(Color.BLACK)
}

/**
 * How far the first line of chrome sits below the top edge of the sheet — the
 * diary page's imprint row, and the running head of every page reached from it.
 *
 * One value for both, because the two have to land on the same line: tapping a
 * label on the page and arriving on the screen it opens should not move that
 * line down the paper. ~20px on the Note Air 2's panel — enough that the line
 * reads as sitting on paper rather than printed into the bezel.
 */
fun Context.chromeTopInset(): Int = dp(13)

/**
 * The line across the top of a page: what this page is, and the way off it.
 *
 * Sits exactly where MainActivity's chrome row sits — same [chromeTopInset],
 * same caption type — so tapping a label on the diary page and arriving here
 * does not move that line down the paper. Horizontally it sits on the text
 * block's own left edge, the way a running head sits over the column it belongs
 * to, and it closes with the same hairline the entries below it are ruled off
 * with.
 *
 * The way back opens the line and belongs to the head itself, not to the
 * screen: these windows hide the navigation bar the way the page does, so a
 * screen that spends the head on something else — saving, creating — is a
 * screen the writer can be stuck on. [onAction] is for what this page *does*;
 * leaving it is never that page's decision to skip.
 *
 * Back sits at the left margin and the action at the right, a column apart:
 * on a panel with no press state, a tap that lands one word off should not be
 * able to turn "leave without saving" into "save".
 *
 * [onBack] exists for the one screen that has something to ask before it goes;
 * everything else leaves the default and simply finishes.
 */
fun Activity.runningHead(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: () -> Unit = { finish() },
): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            // The padding is the tap target: a 16sp caption is a small thing to
            // hit with a stylus, so the word carries the space beside it.
            caption("‹ quay lại").apply {
                setPadding(0, chromeTopInset(), dp(20), dp(6))
                setOnClickListener { onBack() }
            },
        )
        addView(
            caption(title).apply { setPadding(dp(8), chromeTopInset(), 0, dp(6)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (action != null) {
            addView(
                caption(action).apply {
                    setPadding(dp(24), chromeTopInset(), 0, dp(6))
                    setOnClickListener { onAction?.invoke() }
                },
            )
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(48), 0, dp(48), 0)
        addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            View(this@runningHead).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            },
        )
    }
}

/**
 * A page: a [runningHead] pinned to the top of the sheet, and [body] scrolling
 * under it.
 *
 * Pinned rather than scrolled with the body because the head carries the way
 * out — a transcript long enough to scroll its own "quay lại" off the top would
 * otherwise strand the writer on a window with no navigation bar.
 *
 * [body] brings its own vertical rhythm: it is laid out flush against the head's
 * rule, and each kind of entry sets the space above itself.
 */
fun Activity.paperPage(head: View, body: View): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Color.WHITE)
    addView(
        head,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    addView(
        ScrollView(this@paperPage).apply {
            addView(
                body,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
}

/**
 * The column the body of a page is set in: the text block, ruled off the head
 * above it and given room to breathe at the foot.
 */
fun Activity.textBlock(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(48), 0, dp(48), dp(56))
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
