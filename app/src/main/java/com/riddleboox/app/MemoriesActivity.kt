package com.riddleboox.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.tools.MemoryEntry
import com.riddleboox.app.tools.readMemories
import com.riddleboox.app.tools.writeMemories
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What one agent has chosen to remember on purpose, read back as a list —
 * the same `memories.jsonl` [com.riddleboox.app.tools.MemoryTools]'
 * `remember`/`forget_memory` tools write to.
 *
 * Same list-then-detail shape as [com.riddleboox.app.history.HistoryActivity]
 * and [com.riddleboox.app.history.TranscriptActivity]: a scrolling column,
 * newest first, each entry opening what it holds on a tap. A memory is a
 * sentence or two by design (see `MEMORY_SENSE` in
 * [com.riddleboox.app.reply.Conversation]), so "in full" is a dialog rather
 * than another screen.
 *
 * Forgetting no longer requires going back to the page and asking the agent
 * to call `forget_memory` itself: the detail dialog offers "quên đi" too,
 * writing straight to `memories.jsonl` via [writeMemories] — the same atomic
 * rewrite [com.riddleboox.app.tools.MemoryTools] uses, just without its
 * id-prefix guessing, since here the exact [MemoryEntry.id] shown is the one
 * removed.
 *
 * Reads the store fresh on every [onResume] and holds nothing else beyond
 * [workspace], kept only so a delete doesn't need to reload the agent: the
 * writer reaches this only from the agents screen, and it is never open while
 * the diary itself could be writing to the same file.
 */
class MemoriesActivity : Activity() {

    private lateinit var agentStore: AgentStore
    private lateinit var column: LinearLayout
    private var agentId: String = ""
    private var agentName: String = ""
    private var workspace: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        agentStore = AgentStore(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID).orEmpty()
        agentName = intent.getStringExtra(EXTRA_AGENT_NAME).orEmpty()
        column = textBlock()
        setContentView(paperPage(runningHead("$agentName · đã nhớ"), column))
    }

    override fun onResume() {
        super.onResume()
        val agent = agentStore.load(agentId)
        workspace = agent?.workspace
        show(loadEntries())
    }

    private fun loadEntries(): List<MemoryEntry> {
        val ws = workspace ?: return emptyList()
        return readMemories(ws).sortedByDescending { it.ms }
    }

    private fun show(entries: List<MemoryEntry>) {
        column.removeAllViews()
        if (entries.isEmpty()) {
            column.addView(
                TextView(this).apply {
                    text = "agent này chưa nhớ điều gì"
                    textSize = 18f
                    typeface = Typeface.SERIF
                    setTextColor(Color.BLACK)
                    setPadding(0, dp(32), 0, 0)
                },
            )
            return
        }
        for (entry in entries) column.addView(row(entry))
    }

    /** One memory as a line in the list: what was kept, and when it was learned. */
    private fun row(entry: MemoryEntry): View {
        val content = TextView(this).apply {
            text = entry.content
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textSize = 20f
            typeface = Typeface.SERIF
            setTextColor(Color.BLACK)
        }
        val note = TextView(this).apply {
            text = DAY_AND_TIME.format(Date(entry.ms))
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
            addView(content)
            addView(note)
            addView(
                View(this@MemoriesActivity).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                },
            )
            setOnClickListener { showDetail(entry) }
        }
    }

    private fun showDetail(entry: MemoryEntry) {
        AlertDialog.Builder(this)
            .setTitle(DAY_AND_TIME.format(Date(entry.ms)))
            .setMessage(entry.content)
            .setPositiveButton("được", null)
            .setNegativeButton("quên đi") { _, _ -> confirmForget(entry) }
            .show()
    }

    /**
     * Asked before removing, same as [com.riddleboox.app.history.TranscriptActivity]'s
     * "đốt": the file is the only copy, and there is no undo.
     */
    private fun confirmForget(entry: MemoryEntry) {
        AlertDialog.Builder(this)
            .setMessage("Quên điều này? Không lấy lại được.\n\n${entry.content}")
            .setNegativeButton("thôi", null)
            .setPositiveButton("quên") { _, _ -> forget(entry) }
            .show()
    }

    private fun forget(entry: MemoryEntry) {
        val ws = workspace ?: return
        writeMemories(ws, readMemories(ws).filterNot { it.id == entry.id })
        show(loadEntries())
    }

    companion object {
        private const val EXTRA_AGENT_ID = "com.riddleboox.app.MEMORIES_AGENT_ID"
        private const val EXTRA_AGENT_NAME = "com.riddleboox.app.MEMORIES_AGENT_NAME"

        private val DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

        fun intent(context: Context, agentId: String, agentName: String): Intent =
            Intent(context, MemoriesActivity::class.java)
                .putExtra(EXTRA_AGENT_ID, agentId)
                .putExtra(EXTRA_AGENT_NAME, agentName)
    }
}
