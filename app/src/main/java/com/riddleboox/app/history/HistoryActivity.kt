package com.riddleboox.app.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diary's table of contents: every conversation it has had, newest first,
 * each one a line the writer can turn back to.
 *
 * Same sheet of paper as everywhere else — see `ui/Paper.kt`. A column of
 * entries rather than a `ListView`: the whole history is a few dozen lines of
 * text, and a scrolling column of them costs less code than an adapter and
 * reads more like an index page than a settings screen.
 *
 * Holds no state of its own. It reads the store on every [onResume], so a
 * conversation burnt on the screen behind it is simply gone when this one comes
 * back.
 */
class HistoryActivity : Activity() {

    private lateinit var store: ConversationStore
    private lateinit var column: LinearLayout
    private var agentId: String = "chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        store = ConversationStore(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID).orEmpty().ifBlank { "chat" }
        column = textBlock()
        setContentView(paperPage(runningHead("lịch sử"), column))
    }

    override fun onResume() {
        super.onResume()
        show(store.list().filter { it.agentId == agentId })
    }

    private fun show(conversations: List<StoredConversation>) {
        column.removeAllViews()
        if (conversations.isEmpty()) {
            column.addView(
                TextView(this).apply {
                    text = "chưa có trang nào được viết"
                    textSize = 18f
                    typeface = Typeface.SERIF
                    setTextColor(Color.BLACK)
                    setPadding(0, dp(32), 0, 0)
                },
            )
            return
        }
        for (conversation in conversations) column.addView(entry(conversation))
    }

    /**
     * One conversation as a line in the index: its opening words, and under
     * them the day it was written and how long it ran.
     */
    private fun entry(conversation: StoredConversation): View {
        val opening = TextView(this).apply {
            text = conversation.title.ifBlank { "trang không đọc được" }
            maxLines = 2
            textSize = 20f
            typeface = Typeface.SERIF
            setTextColor(Color.BLACK)
        }
        val note = TextView(this).apply {
            text = writtenOn(conversation)
            textSize = 15f
            typeface = Typeface.SERIF
            letterSpacing = 0.06f
            isAllCaps = true
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, dp(10))
            addView(opening)
            addView(note)
            addView(
                View(this@HistoryActivity).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                },
            )
            setOnClickListener {
                startActivityForResult(TranscriptActivity.intent(context, conversation.id), REQUEST_READ)
            }
        }
    }

    /**
     * Passes a resumed conversation up to the diary and closes: the writer
     * tapped "tiếp tục" two screens down, and what they expect to see next is
     * the page, not the index they went through to get there.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_READ || resultCode != RESULT_OK) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESUME_ID, data?.getStringExtra(EXTRA_RESUME_ID)))
        finish()
    }

    private fun writtenOn(conversation: StoredConversation): String =
        DAY_AND_TIME.format(Date(conversation.startedAtMs)) + " · " + conversation.turns.size + " lượt"

    companion object {
        private const val REQUEST_READ = 1

        /** The conversation the diary should take up again, on a RESULT_OK. */
        const val EXTRA_RESUME_ID = "com.riddleboox.app.history.RESUME_ID"
        private const val EXTRA_AGENT_ID = "com.riddleboox.app.history.AGENT_ID"

        private val DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

        fun intent(context: Context, agentId: String = "chat"): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_AGENT_ID, agentId)
    }
}
