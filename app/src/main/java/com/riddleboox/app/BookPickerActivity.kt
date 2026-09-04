package com.riddleboox.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.OnyxLibrary
import com.riddleboox.app.library.shelfLine
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.hairlineWidth
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperButton
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock

/**
 * The writer's BOOX shelf as a page to pick one book from — the way into a
 * book companion session ([MainActivity.intent] with a book id).
 *
 * A page of its own rather than a dialog for the same reason every other list
 * in this app is one ([MemoriesActivity], [com.riddleboox.app.history.HistoryActivity]):
 * a system dialog brings its own grey theme, a radio button per row and type
 * too small for a stylus, none of which belongs on this paper. Here each book
 * is a full-width row in the same rhythm as an agent entry, and the whole row
 * is the tap target.
 *
 * Reads NeoReader's provider off the UI thread once per opening. Everything
 * the shelf can be — still opening, empty, unreachable, or a list — is shown
 * in the column under the running head, so the page never blocks on a dialog
 * the writer has to dismiss before they can leave.
 *
 * Hands the choice back as a result rather than starting the session itself:
 * the screen that opened this one owns what a chosen book means.
 */
class BookPickerActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        column = textBlock()
        setContentView(paperPage(runningHead("choose a book"), column))
        load()
    }

    private fun load() {
        showNote("opening your BOOX library…")
        Thread {
            val result = runCatching { OnyxLibrary(contentResolver).books() }
            runOnUiThread {
                // A late provider answer must not touch a page the writer has left.
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(::show) { showFailure() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun show(books: List<Book>) {
        if (books.isEmpty()) {
            showNote("No books were found in your BOOX library.")
            return
        }
        column.removeAllViews()
        for (book in books) column.addView(row(book))
    }

    private fun showFailure() {
        showNote("Couldn't open the BOOX library. Make sure NeoReader is available, then try again.")
        column.addView(
            paperButton("try again") { load() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
    }

    /** Replaces the column with one line of prose — the page's own way of saying what is going on. */
    private fun showNote(text: String) {
        column.removeAllViews()
        column.addView(
            TextView(this).apply {
                this.text = text
                textSize = 18f
                typeface = Typeface.SERIF
                setTextColor(Color.BLACK)
                setPadding(0, dp(32), 0, 0)
            },
        )
    }

    /** One book as a line on the shelf: its title, and under it who wrote it and how far the reader is. */
    private fun row(book: Book): View {
        val title = TextView(this).apply {
            text = book.title
            textSize = 21f
            typeface = Typeface.SERIF
            setTextColor(Color.BLACK)
        }
        val line = book.shelfLine()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(10))
            addView(title)
            if (line.isNotEmpty()) {
                addView(
                    TextView(this@BookPickerActivity).apply {
                        text = line
                        textSize = 15f
                        typeface = Typeface.SERIF
                        letterSpacing = 0.06f
                        isAllCaps = true
                        setTextColor(Color.BLACK)
                        setPadding(0, dp(4), 0, 0)
                    },
                )
            }
            addView(
                View(this@BookPickerActivity).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairlineWidth()).apply {
                    topMargin = dp(12)
                },
            )
            setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_BOOK_ID, book.id))
                finish()
            }
        }
    }

    companion object {
        /** The chosen [Book.id], on the result of a [RESULT_OK] return. */
        const val EXTRA_BOOK_ID = "com.riddleboox.app.PICKED_BOOK_ID"

        fun intent(context: Context): Intent = Intent(context, BookPickerActivity::class.java)
    }
}
