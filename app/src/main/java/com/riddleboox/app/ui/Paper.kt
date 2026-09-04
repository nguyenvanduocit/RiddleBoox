package com.riddleboox.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
 * The page is laid out edge to edge — the decor no longer fits it inside the
 * system bars — and the bars are asked to hide, coming back only as a
 * transient overlay on a swipe. Hiding is a one-shot request: a dialog or the
 * soft keyboard takes the window's focus and can leave the bars showing when
 * it hands focus back, so the request is made again each time the window
 * regains focus.
 *
 * Edge to edge also means the window no longer moves or shrinks anything for
 * the bars or the keyboard; each page does that itself. [holdUnderSystemBars]
 * keeps a line of chrome below a bar the firmware refuses to hide, and
 * [holdAboveKeyboard] gives the keyboard its own room at the foot of the page,
 * so a focused field is scrolled into view instead of being covered. No view
 * may take y = 0 for the edge of the paper. The keyboard itself is kept off
 * the page by [keepPageVisible]: the full-screen editor it can put up is what
 * took the running head, "save" included, off Settings on the BOOX Go line.
 */
fun Activity.openPaperWindow() {
    // Before anything installs the decor: setDecorFitsSystemWindows does.
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val bars = WindowCompat.getInsetsController(window, window.decorView)
    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    bars.hide(WindowInsetsCompat.Type.systemBars())
    window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) bars.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Keeps a line of chrome below a status bar the firmware will not hide.
 *
 * The page is edge to edge, so its top edge is the panel's — and some firmware
 * (the Go 7 Color has been reported doing it) keeps its status bar on screen
 * over a page that asked for it to go. This view's top padding becomes
 * [topBase] plus whatever the bars take, which is nothing when they hide as
 * asked: the page then looks exactly as it does without the listener. The base
 * is applied at once as well, so anything that reads this view's padding
 * before the first insets arrive sees the same number it always did.
 *
 * The insets are passed on untouched so every view below still hears them.
 */
fun View.holdUnderSystemBars(topBase: Int) {
    setPadding(paddingLeft, topBase, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, topBase + bars.top, v.paddingRight, v.paddingBottom)
        insets
    }
}

/**
 * Gives the soft keyboard its own room at the foot of this view.
 *
 * An edge-to-edge window is not resized for the keyboard, and `adjustResize`
 * on its own does nothing for it: the keyboard simply covers the lower part
 * of the page, focused field and all, with the window neither resized nor
 * panned (seen on Note Air 2 and on a stock Android 13 emulator). Padding the
 * page's root by the keyboard's height instead shrinks the body under the
 * head, and a ScrollView that has just got shorter scrolls its focused child
 * into view on its own.
 */
fun View.holdAboveKeyboard() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, keyboard.bottom)
        insets
    }
}

/**
 * Keeps the keyboard from taking the whole screen while this field is typed
 * into.
 *
 * Left to itself an IME may go full screen — on a landscape-shaped window, or
 * whenever the keyboard's own judgement says the page is too short — and put
 * up its own editor and a DONE button in place of the page. Nothing of the
 * page survives that: no running head, no "save", not even a status bar, which
 * is exactly what the BOOX Go line showed after typing an api key. Every field
 * on a page asks for both: no full-screen mode at all, and no extracted editor
 * should the keyboard insist on one anyway.
 */
fun EditText.keepPageVisible() {
    imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
}

/**
 * The quiet caption type this app labels everything with.
 *
 * Black at full opacity, never faded: an alpha-faded caption reads as "quiet"
 * on a normal display, but e-ink's coarser grayscale dithers a faded black down
 * to something that isn't reliably legible at 16sp. The restraint comes from
 * the small size and the serif caps instead.
 *
 * [icon] sets a mark before the word rather than in place of it. A row of caps
 * at one size is a wall to scan; a mark gives each label a shape the eye can be
 * drawn to and find again without reading. The word stays because a mark alone
 * has to be learned. What survives the panel at this size is a narrow set —
 * solid shapes or a 2-unit stroke, nothing finer; see `ic_chrome_*.xml`.
 */
fun Context.caption(label: String, @DrawableRes icon: Int = 0): TextView = TextView(this).apply {
    text = label
    textSize = 16f
    // UI chrome is sans-serif medium for reliable glyph shapes on BOOX's
    // grayscale panel; long-form page content keeps the serif face below.
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.08f
    isAllCaps = true
    setTextColor(Color.BLACK)
    if (icon != 0) {
        // The mark is measured off the type, not off the screen. BOOX lets the
        // reader scale system text, and this panel ships scaled down — a mark
        // fixed in dp comes out half again taller than the caps beside it and
        // reads as a button stuck to a word. [TextView.getTextSize] is already
        // px with that scale in it, and one em is the box the drawing was cut
        // to sit inside: caps fill about 0.7 of it, the glyphs about 0.75.
        val mark = requireNotNull(context.getDrawable(icon))
        val em = textSize.toInt()
        mark.setBounds(0, 0, em, em)
        // Relative, not left/right: the mark leads the word in either
        // direction the page is ever set in.
        setCompoundDrawablesRelative(mark, null, null, null)
        // Wide enough that the mark reads as its own thing and not as the
        // word's first letter; the caps' own letterSpacing is 0.08 of an em.
        compoundDrawablePadding = dp(6)
    }
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
        // The row takes a bar the firmware keeps; the captions keep their own
        // [chromeTopInset], because that padding is their tap target.
        holdUnderSystemBars(0)
        addView(
            // The padding is the tap target: a 16sp caption is a small thing to
            // hit with a stylus, so the word carries the space beside it.
            caption("‹ back").apply {
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
        setPadding(dp(32), 0, dp(32), 0)
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
 * out — a transcript long enough to scroll its own "back" off the top would
 * otherwise strand the writer on a window with no navigation bar.
 *
 * [body] brings its own vertical rhythm: it is laid out flush against the head's
 * rule, and each kind of entry sets the space above itself.
 */
fun Activity.paperPage(head: View, body: View): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Color.WHITE)
    // On the root, not the ScrollView: the foot padding is what shrinks the
    // body, and only a body that has shrunk scrolls its focused field up.
    holdAboveKeyboard()
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
    setPadding(dp(32), 0, dp(32), dp(56))
}

/**
 * The one button shape this app has: a word inside a 2dp black rule, on
 * paper. No fill and no press state — e-ink has no time for either — so the
 * padding is the affordance: 24dp either side and 12dp above and below is what
 * makes a word a target for a stylus, the same way [runningHead]'s captions
 * carry their tap area as padding.
 *
 * Plain sans, not the caption's caps: a button is an instruction, not a label,
 * and reads faster set the way a sentence is.
 */
fun Context.paperButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 18f
    setTextColor(Color.BLACK)
    gravity = Gravity.CENTER
    setPadding(dp(24), dp(12), dp(24), dp(12))
    background = GradientDrawable().apply {
        setStroke(dp(2), Color.BLACK)
        setColor(Color.TRANSPARENT)
    }
    setOnClickListener { onClick() }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
