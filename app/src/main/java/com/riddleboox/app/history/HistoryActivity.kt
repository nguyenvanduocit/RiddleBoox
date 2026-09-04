package com.riddleboox.app.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.hairlineWidth
import com.riddleboox.app.ui.keepPageVisible
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diary's table of contents: every conversation it has had, newest first,
 * each one a line the writer can turn back to — with a search box above the
 * list for the writer's own lookups, the way [com.riddleboox.app.tools.DiaryMemory.recall]
 * already lets the model search this same store.
 *
 * Same sheet of paper as everywhere else — see `ui/Paper.kt`. A column of
 * entries rather than a `ListView`: the whole history is a few dozen lines of
 * text, and a scrolling column of them costs less code than an adapter and
 * reads more like an index page than a settings screen.
 *
 * Holds one piece of state past what it reads from the store: [allConversations],
 * the current agent's conversations before the search box narrows them. Kept so
 * each keystroke re-filters in memory rather than re-reading the store — the
 * store itself is still re-read on every [onResume], so a conversation burnt on
 * the screen behind it is simply gone when this one comes back.
 */
class HistoryActivity : Activity() {

    private lateinit var store: ConversationStore
    private lateinit var column: LinearLayout
    private lateinit var searchField: EditText
    private var agentId: String = "chat"

    /** [agentId]'s conversations as [onResume] last read them, unnarrowed by [searchField]. */
    private var allConversations: List<StoredConversation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        store = ConversationStore(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID).orEmpty().ifBlank { "chat" }
        val agentName = AgentStore(this).load(agentId)?.name
        column = textBlock()
        val searchRow = searchRow()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                searchRow,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(column)
        }
        val title = if (agentName != null) "$agentName · history" else "history"
        setContentView(paperPage(runningHead(title, "export all", onAction = { exportAll() }), body))
    }

    override fun onResume() {
        super.onResume()
        allConversations = store.list().filter { it.agentId == agentId }
        show(allConversations.matching(searchField.text?.toString().orEmpty()))
    }

    /**
     * The search row itself: kept out of [column] so [show]'s `removeAllViews`
     * never touches it, sitting above the entries it narrows.
     *
     * [searchField] carries the same input styling as
     * [com.riddleboox.app.settings.LockActivity]'s PIN field — transparent
     * background, black text, no border — this app has no chrome for input
     * fields beyond that. The row carries the left/right inset ([dp]`(48)`,
     * matching [textBlock]'s) because it sits beside [column] in `body` rather
     * than inside it, so it has no padded parent to inherit that inset from.
     *
     * Beside the field, a "clear" caption that only shows once there's a query to
     * clear — tapping it empties [searchField], which re-fires the
     * [TextWatcher] below and puts the row back to its resting, buttonless
     * state on its own; no extra code needed to hide it again.
     */
    private fun searchRow(): LinearLayout {
        val clearAction = caption("clear").apply {
            visibility = View.GONE
            setPadding(dp(12), dp(24), dp(48), dp(8))
            setOnClickListener { searchField.text?.clear() }
        }
        searchField = EditText(this).apply {
            hint = "search history"
            setSingleLine()
            keepPageVisible()
            textSize = 17f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(48), dp(24), dp(12), dp(8))
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString().orEmpty()
                        clearAction.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                        show(allConversations.matching(query))
                    }
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearAction)
        }
    }

    private fun show(conversations: List<StoredConversation>) {
        column.removeAllViews()
        if (conversations.isEmpty()) {
            column.addView(
                TextView(this).apply {
                    text = "no pages have been written yet"
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
            text = conversation.title.ifBlank { "an unreadable page" }
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
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairlineWidth()).apply {
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
     * tapped "continue" two screens down, and what they expect to see next is
     * the page, not the index they went through to get there.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_READ || resultCode != RESULT_OK) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESUME_ID, data?.getStringExtra(EXTRA_RESUME_ID)))
        finish()
    }

    /**
     * [StoredConversation.updatedAtMs], not [StoredConversation.startedAtMs]:
     * the list above this line is sorted newest-answered-first
     * ([ConversationStore.list]), so the date under each entry has to agree
     * with that ordering — a resumed evening dated by the day it *began*
     * would show an older date sitting above a genuinely older, untouched one.
     */
    private fun writtenOn(conversation: StoredConversation): String =
        DAY_AND_TIME.format(Date(conversation.updatedAtMs)) + " · " + conversation.turns.size + " turns"

    /**
     * Writes every conversation for [agentId] out as one plain-text file under
     * `cacheDir/exports` and hands it to the share sheet — the whole index at
     * once, the way [TranscriptActivity.share] hands over a single evening.
     *
     * Re-reads [store] rather than the list [show] last drew, for the same
     * reason [TranscriptActivity.share] re-reads instead of reusing [onCreate]'s
     * snapshot: a tap on "export all" a while after the screen opened should
     * export what's on disk right now, not what was there when this screen was
     * last resumed.
     */
    private fun exportAll() {
        val conversations = store.list().filter { it.agentId == agentId }
        val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "all-$agentId.txt")
        file.writeText(conversations.toPlainText())

        val uri = FileProvider.getUriForFile(this, "$packageName.onyx.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share the whole history"))
    }

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
