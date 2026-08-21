package com.riddleboox.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun args(vararg values: Pair<String, String>) =
        JsonObject(values.associate { it.first to JsonPrimitive(it.second) })

    private fun call(tools: WorkspaceTools, name: String, vararg values: Pair<String, String>): String =
        runBlocking { tools.call(name, args(*values)) }

    @Test
    fun `artifacts can be written edited searched and read`() {
        val tools = WorkspaceTools(folder.root)
        assertTrue(call(tools, "workspace_write", "path" to "notes/today.md", "content" to "Met Tom\nRemember the library." ).startsWith("Wrote"))
        assertTrue(call(tools, "workspace_edit", "path" to "notes/today.md", "old_text" to "Tom", "new_text" to "the diary").contains("Replaced 1"))
        assertTrue(call(tools, "workspace_read", "path" to "notes/today.md").contains("the diary"))
        assertTrue(call(tools, "workspace_search", "query" to "today").contains("notes/today.md"))
        assertTrue(call(tools, "workspace_grep", "query" to "library").contains("notes/today.md:2"))
    }

    @Test
    fun `regex search is supported and paths cannot escape`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "memory.md", "content" to "Idea 42")
        assertTrue(call(tools, "workspace_regex_search", "pattern" to "idea\\s+\\d+").contains("memory.md"))
        assertTrue(call(tools, "workspace_read", "path" to "../outside.txt").contains("escapes"))
    }

    @Test
    fun `deleting an artifact leaves the workspace itself intact`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "scratch.txt", "content" to "x")
        assertTrue(call(tools, "workspace_delete", "path" to "scratch.txt").contains("Deleted"))
        assertTrue(call(tools, "workspace_stat", "path" to "scratch.txt").contains("does not exist"))
        assertTrue(folder.root.isDirectory)
    }
}
