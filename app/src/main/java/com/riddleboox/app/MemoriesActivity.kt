package com.riddleboox.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.tools.MemoryEntry
import com.riddleboox.app.tools.matching
import com.riddleboox.app.tools.readMemories
import com.riddleboox.app.tools.toPlainText
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
 * to call `forget_memory` itself: the detail dialog offers "forget" too,
 * writing straight to `memories.jsonl` via [writeMemories] — the same atomic
 * rewrite [com.riddleboox.app.tools.MemoryTools] uses, just without its
 * id-prefix guessing, since here the exact [MemoryEntry.id] shown is the one
 * removed.
 *
 * Reads the store fresh on every [onResume] and holds nothing else beyond
 * [workspace], kept only so a delete doesn't need to reload the agent: the
 * writer reaches this only from the agents screen, and it is never open while
 * the diary itself could be writing to the same file.
 *
 * Carries a search box above the list, the same shape as
 * [com.riddleboox.app.history.HistoryActivity]'s: [allEntries] holds what
 * [onResume] last read, unnarrowed, and [searchField] filters it in memory
 * on every keystroke via [com.riddleboox.app.tools.matching] rather than
 * re-reading the store — a memory list grows the same way a history does,
 * and deserves the same way back to one entry in it. A "clear" caption beside
 * the field appears only once there is text to clear, so getting back to
 * the full list never requires deleting a query by hand.
 */
class MemoriesActivity : Activity() {

    private lateinit var agentStore: AgentStore
    private lateinit var column: LinearLayout
    private lateinit var searchField: EditText
    private var agentId: String = ""
    private var agentName: String = ""
    private var workspace: File? = null

    /** [agentId]'s memories as [onResume] last read them, unnarrowed by [searchField]. */
    private var allEntries: List<MemoryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        agentStore = AgentStore(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID).orEmpty()
        agentName = intent.getStringExtra(EXTRA_AGENT_NAME).orEmpty()
        column = textBlock()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                searchRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(column)
        }
        setContentView(paperPage(runningHead("$agentName · memories", "export all", onAction = { exportAll() }), body))
    }

    override fun onResume() {
        super.onResume()
        val agent = agentStore.load(agentId)
        workspace = agent?.workspace
        allEntries = loadEntries()
        show(allEntries.matching(searchField.text?.toString().orEmpty()))
    }

    /**
     * The search box plus its clear button: kept out of [column] so [show]'s
     * `removeAllViews` never touches either, sitting above the entries
     * [searchField] narrows.
     *
     * Same input-field styling as [com.riddleboox.app.history.HistoryActivity]'s
     * `searchField` — transparent background, black text, no border. The
     * same [dp]`(48)` left/right inset now splits between the two views
     * instead of living on [searchField] alone: the field carries it on the
     * left, the row itself carries it on the right, so the margin holds
     * steady whether or not "clear" is showing rather than collapsing when a
     * `GONE` view drops its own padding.
     *
     * "clear" is a [caption] rather than a bespoke icon button — no image
     * assets in this app, and a caption is already how every other action
     * in the chrome (see [com.riddleboox.app.ui.runningHead]) reads on
     * BOOX's panel. It starts `GONE` and only turns `VISIBLE` once there is
     * a query to clear, toggled from the same [TextWatcher.afterTextChanged]
     * that already runs the filter, so the two stay in lockstep by
     * construction rather than by a second listener to keep in sync.
     */
    private fun searchRow(): View {
        val clear = caption("clear").apply {
            visibility = View.GONE
            setPadding(dp(16), dp(24), 0, dp(8))
            setOnClickListener { searchField.text?.clear() }
        }
        searchField = EditText(this).apply {
            hint = "search memories"
            setSingleLine()
            textSize = 17f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(48), dp(24), 0, dp(8))
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString().orEmpty()
                        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                        show(allEntries.matching(query))
                    }
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(48), 0)
            addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
        }
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
                    text = "this agent has not remembered anything yet"
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
            .setPositiveButton("ok", null)
            .setNegativeButton("forget") { _, _ -> confirmForget(entry) }
            .setNeutralButton("copy") { _, _ -> copy(entry) }
            .show()
    }

    private fun copy(entry: MemoryEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("memory", entry.content))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Asked before removing, same as [com.riddleboox.app.history.TranscriptActivity]'s
     * "burn": the file is the only copy, and there is no undo.
     */
    private fun confirmForget(entry: MemoryEntry) {
        AlertDialog.Builder(this)
            .setMessage("Forget this? It cannot be brought back.\n\n${entry.content}")
            .setNegativeButton("cancel", null)
            .setPositiveButton("forget") { _, _ -> forget(entry) }
            .show()
    }

    private fun forget(entry: MemoryEntry) {
        val ws = workspace ?: return
        writeMemories(ws, readMemories(ws).filterNot { it.id == entry.id })
        allEntries = loadEntries()
        show(allEntries.matching(searchField.text?.toString().orEmpty()))
    }

    /**
     * Writes every memory for [agentId] out as one plain-text file under
     * `cacheDir/exports` and hands it to the share sheet — the same shape as
     * [com.riddleboox.app.history.HistoryActivity.exportAll], the diary's own
     * "export all".
     *
     * Re-reads [workspace] rather than [allEntries], for the same reason
     * [com.riddleboox.app.history.HistoryActivity.exportAll] re-reads its
     * store: a tap on "export all" a while after the screen opened should
     * export what's on disk right now.
     */
    private fun exportAll() {
        val ws = workspace ?: return
        val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "memories-$agentId.txt")
        file.writeText(readMemories(ws).sortedByDescending { it.ms }.toPlainText())

        val uri = FileProvider.getUriForFile(this, "$packageName.onyx.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share memories"))
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
