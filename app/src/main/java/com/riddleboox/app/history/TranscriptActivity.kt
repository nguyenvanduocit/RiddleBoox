package com.riddleboox.app.history

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One conversation read back as print: the writer's lines as the diary read
 * them off the page, and the diary's answers under each.
 *
 * Print rather than the handwriting it happened in, because the two are for
 * different things. The page is where a conversation is *had* — slow ink,
 * one reply at a time. This is where one is *found* again, and finding wants a
 * whole evening at a glance.
 *
 * From here a conversation is either taken up again ([EXTRA_RESUME_ID] back to
 * [HistoryActivity], which passes it on to the diary) or burnt.
 */
class TranscriptActivity : Activity() {

    private lateinit var store: ConversationStore
    private lateinit var conversationId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        store = ConversationStore(this)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val conversation = store.load(conversationId)
        if (conversation == null) {
            // Burnt or damaged between the index being drawn and this tap.
            finish()
            return
        }

        val column = textBlock().apply {
            for (turn in conversation.turns) {
                addView(written(turn.transcript))
                addView(answered(turn.reply))
            }
            addView(actions())
        }
        val day = DAY_AND_TIME.format(Date(conversation.startedAtMs))
        setContentView(paperPage(runningHead(day), column))
    }

    /** What the writer wrote, upright and flush left, the way it sat on the page. */
    private fun written(transcript: String): TextView = TextView(this).apply {
        text = transcript.ifBlank { "(mực nhoè)" }
        textSize = 19f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        setPadding(0, dp(28), 0, 0)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    /** The diary's own hand: italic and indented, so a glance tells the voices apart. */
    private fun answered(reply: String): TextView = TextView(this).apply {
        text = reply
        textSize = 19f
        setTypeface(Typeface.SERIF, Typeface.ITALIC)
        setTextColor(Color.BLACK)
        setPadding(dp(24), dp(10), 0, 0)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun actions(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(48), 0, 0)
        addView(
            caption("tiếp tục").apply {
                textSize = 16f
                setPadding(0, dp(12), dp(40), dp(12))
                setOnClickListener { resume() }
            },
        )
        addView(
            caption("đốt").apply {
                textSize = 16f
                setPadding(dp(40), dp(12), dp(12), dp(12))
                setOnClickListener { confirmBurn() }
            },
        )
    }

    private fun resume() {
        setResult(RESULT_OK, Intent().putExtra(HistoryActivity.EXTRA_RESUME_ID, conversationId))
        finish()
    }

    /**
     * Asked first, because there is no undo: the file is the only copy, and a
     * stylus tap is easy to make by accident while scrolling.
     */
    private fun confirmBurn() {
        AlertDialog.Builder(this)
            .setMessage("Đốt cuộc trò chuyện này? Không lấy lại được.")
            .setNegativeButton("thôi", null)
            .setPositiveButton("đốt") { _, _ ->
                store.delete(conversationId)
                finish()
            }
            .show()
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "com.riddleboox.app.history.CONVERSATION_ID"

        private val DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, TranscriptActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
