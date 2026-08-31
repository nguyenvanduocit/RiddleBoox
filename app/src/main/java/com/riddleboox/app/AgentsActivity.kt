package com.riddleboox.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.riddleboox.app.agent.DEFAULT_AGENT_GREETINGS
import com.riddleboox.app.agent.AgentCapability
import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentSelectionStore
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.agent.toPlainText
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.OnyxLibrary
import com.riddleboox.app.tools.readMemories
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.io.File

/** Selects agents and edits the user-defined RiddleBoox agents. */
class AgentsActivity : Activity() {

    private lateinit var store: AgentStore
    private lateinit var selection: AgentSelectionStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        store = AgentStore(this)
        store.ensureDefaults()
        selection = AgentSelectionStore(this)
        column = textBlock()
        setContentView(paperPage(runningHead("agents", "new", onAction = { edit(null) }), column))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(action("open a BOOX book") { pickBook() })
        val selected = selection.read()
        for (agent in store.list()) {
            val title = TextView(this).apply {
                text = if (agent.id == selected) "${agent.name}  · in use" else agent.name
                textSize = 21f
                typeface = Typeface.SERIF
                setTextColor(Color.BLACK)
            }
            val details = TextView(this).apply {
                text = "${agent.id} · ${if (agent.builtin) "built-in" else "custom"} · tools: ${agent.toolIds.joinToString(", ")} · greetings: ${agent.greetings.size}\n${agent.description}"
                textSize = 16f
                typeface = Typeface.SERIF
                setTextColor(Color.BLACK)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(8))
                addView(action("use") { select(agent) })
                addView(action("memories") { startActivity(MemoriesActivity.intent(this@AgentsActivity, agent.id, agent.name)) })
                addView(action("duplicate") { edit(null, template = agent) })
                addView(action("export") { export(agent) })
                if (!agent.builtin) {
                    addView(action("edit") { edit(agent) })
                    addView(action("delete") { confirmDelete(agent) })
                }
            }
            column.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(28), 0, 0)
                    addView(title)
                    addView(details)
                    addView(actions)
                    addView(View(this@AgentsActivity).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1),
                    ))
                },
            )
        }
    }

    private fun action(label: String, onClick: () -> Unit): TextView = caption(label).apply {
        textSize = 16f
        setPadding(0, dp(6), dp(28), dp(6))
        setOnClickListener { onClick() }
    }

    private fun select(agent: AgentDefinition) {
        selection.write(agent.id)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_AGENT_ID, agent.id))
        finish()
    }

    /** Reads NeoReader's shelf away from the UI thread, then lets the writer choose one entry. */
    private fun pickBook() {
        val waiting = AlertDialog.Builder(this)
            .setMessage("opening your BOOX library…")
            .setNegativeButton("cancel", null)
            .show()
        Thread {
            val result = runCatching { OnyxLibrary(contentResolver).books() }
            runOnUiThread {
                // A dismissed wait dialog is the writer changing their mind;
                // a late provider answer must not reopen a dead Activity.
                if (isFinishing || isDestroyed || !waiting.isShowing) return@runOnUiThread
                waiting.dismiss()
                result.getOrNull()?.let(::showBookPicker) ?: showBookLoadError()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun showBookPicker(books: List<Book>) {
        if (books.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("No books were found in your BOOX library.")
                .setPositiveButton("ok", null)
                .show()
            return
        }
        val labels = books.map(::bookLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("open a BOOX book")
            .setSingleChoiceItems(labels, -1) { dialog, which ->
                dialog.dismiss()
                // This starts a separate, local session. It deliberately does
                // not call select(), so the user's normal selected agent stays put.
                startActivity(MainActivity.intent(this, books[which].id))
                finish()
            }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun bookLabel(book: Book): String = buildString {
        append(book.title)
        if (book.authors.isNotBlank()) append("\n").append(book.authors)
        if (book.page > 0 && book.pages > 0) append("\npage ${book.page} of ${book.pages}")
    }

    private fun showBookLoadError() {
        AlertDialog.Builder(this)
            .setMessage("Couldn't open the BOOX library. Make sure NeoReader is available, then try again.")
            .setPositiveButton("ok", null)
            .show()
    }

    /**
     * Writes the agent's definition out as plain text under `cacheDir/exports`
     * and hands the file to whatever the writer picks from the share sheet —
     * same pattern as [com.riddleboox.app.history.TranscriptActivity.share].
     *
     * Exports the definition only ([AgentDefinition.toPlainText]), never
     * [AgentDefinition.workspace]: sharing "how this agent is configured"
     * should not also hand off whatever that agent's tools have written to
     * disk. Available for built-in agents too — reading or backing up a
     * built-in's prompt is a legitimate use even though it cannot be edited.
     */
    private fun export(agent: AgentDefinition) {
        val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "agent-${agent.id}.txt")
        file.writeText(agent.toPlainText())

        val uri = FileProvider.getUriForFile(this, "$packageName.onyx.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share agent"))
    }

    private fun edit(existing: AgentDefinition?, template: AgentDefinition? = existing) {
        if (existing?.builtin == true) return
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val idField = input("id", existing?.id.orEmpty(), singleLine = true).also { form.addView(it.first); form.addView(it.second) }
        if (existing != null) {
            // id là bất biến sau khi tạo (save() luôn dùng existing.id khi update) — khoá field để UI không hứa hẹn điều làm không được.
            idField.second.isEnabled = false
            idField.second.alpha = 0.6f
        }
        val nameField = input("name", template?.name.orEmpty(), singleLine = true).also { form.addView(it.first); form.addView(it.second) }
        val descriptionField = input("description", template?.description.orEmpty(), singleLine = false).also { form.addView(it.first); form.addView(it.second) }
        val selectedTools = (template?.toolIds ?: emptySet()).toMutableSet()
        form.addView(caption("tools (pick capabilities)").apply { setPadding(0, dp(10), 0, dp(2)) })
        form.addView(toolTags(selectedTools))
        form.addView(caption("workspace, memory and drawing are always on for every agent · agent_management belongs to the built-in manager agent only.").apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(2))
        })
        val greetingsField = input(
            "greetings (one line each)",
            (template?.greetings ?: DEFAULT_AGENT_GREETINGS).joinToString("\n"),
            singleLine = false,
        ).also { form.addView(it.first); form.addView(it.second) }
        // "preview": đọc trực tiếp nội dung đang gõ (chưa lưu) để rút ngắn vòng lặp chỉnh-sửa-xem,
        // thay vì phải lưu agent rồi mở trang mới trên MainActivity mới thấy greeting trông ra sao.
        form.addView(
            action("preview") {
                val candidates = parseGreetings(greetingsField.second.text.toString())
                if (candidates.isEmpty()) {
                    Toast.makeText(this, "no greeting lines to preview yet", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, candidates.random(), Toast.LENGTH_LONG).show()
                }
            },
        )
        val promptField = input("system prompt", template?.systemPrompt.orEmpty(), singleLine = false).also { form.addView(it.first); form.addView(it.second) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "new agent" else "edit agent")
            .setView(form)
            .setNegativeButton("cancel", null)
            .setPositiveButton("save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = save(
                    existing = existing,
                    id = idField.second.text.toString(),
                    name = nameField.second.text.toString(),
                    description = descriptionField.second.text.toString(),
                    tools = selectedTools.toSet(),
                    greetings = greetingsField.second.text.toString(),
                    prompt = promptField.second.text.toString(),
                )
                if (error == null) {
                    setResult(RESULT_OK)
                    dialog.dismiss()
                    render()
                } else {
                    AlertDialog.Builder(this).setMessage(error).setPositiveButton("ok", null).show()
                }
            }
        }
        dialog.show()
    }

    private fun save(existing: AgentDefinition?, id: String, name: String, description: String, tools: Set<String>, greetings: String, prompt: String): String? =
        runCatching {
            require(name.trim().isNotEmpty()) { "The agent needs a name." }
            require(prompt.trim().isNotEmpty()) { "The system prompt cannot be empty." }
            require(existing?.builtin != true) { "Built-in agents are read-only." }
            val parsedGreetings = parseGreetings(greetings)
            require(parsedGreetings.isNotEmpty()) { "The agent needs at least one greeting line." }
            if (existing == null) {
                val base = AgentStore.slugFor(id.ifBlank { name })
                var unique = base
                var suffix = 2
                while (store.load(unique) != null) unique = "$base-${suffix++}"
                store.create(unique, name, description, prompt, tools = tools, greetings = parsedGreetings)
            } else {
                store.update(existing.id, name, description, prompt, tools = tools, greetings = parsedGreetings)
            }
        }.exceptionOrNull()?.message

    private fun toolTags(selected: MutableSet<String>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        TOOL_OPTIONS.chunked(2).forEach { rowOptions ->
            val row = LinearLayout(this@AgentsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            rowOptions.forEach { option ->
                row.addView(
                    toolTag(option, selected),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    },
                )
            }
            if (rowOptions.size == 1) {
                row.addView(View(this@AgentsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            addView(row)
        }
    }

    private fun toolTag(option: ToolOption, selected: MutableSet<String>): TextView = TextView(this).apply {
        text = option.label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        isFocusable = true
        refreshToolTag(this, option.id in selected)
        setOnClickListener {
            if (!selected.add(option.id)) selected.remove(option.id)
            refreshToolTag(this, option.id in selected)
        }
    }

    private fun refreshToolTag(tag: TextView, selected: Boolean) {
        tag.setTextColor(if (selected) Color.WHITE else Color.BLACK)
        tag.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (selected) Color.BLACK else Color.WHITE)
            setStroke(dp(1), Color.BLACK)
        }
    }

    private fun parseGreetings(value: String): List<String> = value
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    private fun confirmDelete(agent: AgentDefinition) {
        val memoryCount = readMemories(agent.workspace).size
        val message = if (memoryCount > 0) {
            "Delete ${agent.name}? Its $memoryCount memories go with it. There is no undo."
        } else {
            "Delete ${agent.name} and this agent's whole private workspace?"
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton("cancel", null)
            .setPositiveButton("delete") { _, _ ->
                store.delete(agent.id)
                if (selection.read() == agent.id) selection.write("chat")
                setResult(RESULT_OK)
                render()
            }
            .show()
    }

    private fun input(label: String, value: String, singleLine: Boolean): Pair<TextView, EditText> {
        val labelView = caption(label).apply { setPadding(0, dp(10), 0, dp(2)) }
        val field = EditText(this).apply {
            setText(value)
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(4), 0, dp(4))
            isSingleLine = singleLine
            if (!singleLine) {
                minLines = 3
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }
        return labelView to field
    }

    companion object {
        /** Marks the one action that changed the global selected agent. */
        const val EXTRA_SELECTED_AGENT_ID = "com.riddleboox.app.SELECTED_AGENT_ID"

        private data class ToolOption(val id: String, val label: String)

        private val TOOL_OPTIONS = listOf(
            ToolOption(AgentCapability.LIBRARY, "library"),
            ToolOption(AgentCapability.DILIB, "dilib"),
            ToolOption(AgentCapability.BOOX_NOTES, "boox_notes"),
        )

        fun intent(context: Context): Intent = Intent(context, AgentsActivity::class.java)
    }
}
