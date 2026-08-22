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
import com.riddleboox.app.agent.DEFAULT_AGENT_GREETINGS
import com.riddleboox.app.agent.AgentCapability
import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentSelectionStore
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock

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
        setContentView(paperPage(runningHead("agents", "tạo", onAction = { edit(null) }), column))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        val selected = selection.read()
        for (agent in store.list()) {
            val title = TextView(this).apply {
                text = if (agent.id == selected) "${agent.name}  · đang dùng" else agent.name
                textSize = 21f
                typeface = Typeface.SERIF
                setTextColor(Color.BLACK)
            }
            val details = TextView(this).apply {
                text = "${agent.id} · ${if (agent.builtin) "mặc định" else "tùy chỉnh"} · tools: ${agent.toolIds.joinToString(", ")} · greetings: ${agent.greetings.size}\n${agent.description}"
                textSize = 16f
                typeface = Typeface.SERIF
                setTextColor(Color.BLACK)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(8))
                addView(action("dùng") { select(agent) })
                addView(action("nhớ") { startActivity(MemoriesActivity.intent(this@AgentsActivity, agent.id, agent.name)) })
                addView(action("nhân bản") { edit(null, template = agent) })
                if (!agent.builtin) {
                    addView(action("sửa") { edit(agent) })
                    addView(action("xóa") { confirmDelete(agent) })
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
        setResult(RESULT_OK)
        finish()
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
        val nameField = input("tên", template?.name.orEmpty(), singleLine = true).also { form.addView(it.first); form.addView(it.second) }
        val descriptionField = input("mô tả", template?.description.orEmpty(), singleLine = false).also { form.addView(it.first); form.addView(it.second) }
        val selectedTools = (template?.toolIds ?: setOf(AgentCapability.WORKSPACE)).toMutableSet()
        form.addView(caption("tools (chọn capability)").apply { setPadding(0, dp(10), 0, dp(2)) })
        form.addView(toolTags(selectedTools))
        form.addView(caption("workspace luôn bật · agent_management chỉ dành cho agent quản lý mặc định.").apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(2))
        })
        val greetingsField = input(
            "greetings (mỗi dòng một câu)",
            (template?.greetings ?: DEFAULT_AGENT_GREETINGS).joinToString("\n"),
            singleLine = false,
        ).also { form.addView(it.first); form.addView(it.second) }
        val promptField = input("system prompt", template?.systemPrompt.orEmpty(), singleLine = false).also { form.addView(it.first); form.addView(it.second) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "tạo agent" else "sửa agent")
            .setView(form)
            .setNegativeButton("thôi", null)
            .setPositiveButton("lưu", null)
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
                    AlertDialog.Builder(this).setMessage(error).setPositiveButton("được", null).show()
                }
            }
        }
        dialog.show()
    }

    private fun save(existing: AgentDefinition?, id: String, name: String, description: String, tools: Set<String>, greetings: String, prompt: String): String? =
        runCatching {
            require(name.trim().isNotEmpty()) { "Tên agent không được trống." }
            require(prompt.trim().isNotEmpty()) { "System prompt không được trống." }
            require(existing?.builtin != true) { "Agent mặc định chỉ đọc." }
            val parsedGreetings = parseGreetings(greetings)
            require(parsedGreetings.isNotEmpty()) { "Agent cần ít nhất một câu greeting." }
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
        isClickable = !option.locked
        isFocusable = !option.locked
        refreshToolTag(this, option.id in selected, option.locked)
        if (!option.locked) {
            setOnClickListener {
                if (!selected.add(option.id)) selected.remove(option.id)
                refreshToolTag(this, option.id in selected, option.locked)
            }
        }
    }

    private fun refreshToolTag(tag: TextView, selected: Boolean, locked: Boolean) {
        tag.setTextColor(if (selected) Color.WHITE else Color.BLACK)
        tag.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (selected) Color.BLACK else Color.WHITE)
            setStroke(dp(1), Color.BLACK)
        }
        tag.alpha = if (locked) 0.7f else 1f
    }

    private fun parseGreetings(value: String): List<String> = value
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    private fun confirmDelete(agent: AgentDefinition) {
        AlertDialog.Builder(this)
            .setMessage("Xóa ${agent.name} và toàn bộ workspace riêng của agent này?")
            .setNegativeButton("thôi", null)
            .setPositiveButton("xóa") { _, _ ->
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
        private data class ToolOption(val id: String, val label: String, val locked: Boolean = false)

        private val TOOL_OPTIONS = listOf(
            ToolOption(AgentCapability.WORKSPACE, "workspace", locked = true),
            ToolOption(AgentCapability.LIBRARY, "library"),
            ToolOption(AgentCapability.DILIB, "dilib"),
            ToolOption(AgentCapability.BOOX_NOTES, "boox_notes"),
        )

        fun intent(context: Context): Intent = Intent(context, AgentsActivity::class.java)
    }
}
