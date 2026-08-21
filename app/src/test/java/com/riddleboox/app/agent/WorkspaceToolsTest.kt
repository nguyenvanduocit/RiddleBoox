package com.riddleboox.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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

    // Characterization tests for `grep` (WorkspaceTools.kt), written against the current
    // implementation before a nesting-reduction refactor, to pin down exact behavior so the
    // refactor cannot silently change it.

    @Test
    fun `grep result uses one-based line numbers in path colon line colon text format`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "single.txt", "content" to "hello world")
        val result = call(tools, "workspace_grep", "query" to "hello")
        assertEquals("single.txt:1: hello world", result)
    }

    @Test
    fun `grep finds every match within one file in line order`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "multi.txt", "content" to "alpha\nbeta alpha\ngamma\nalpha end")
        val result = call(tools, "workspace_grep", "query" to "alpha")
        assertEquals("multi.txt:1: alpha\nmulti.txt:2: beta alpha\nmulti.txt:4: alpha end", result)
    }

    @Test
    fun `grep finds matches spread across multiple files`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "one.txt", "content" to "needle here")
        call(tools, "workspace_write", "path" to "two.txt", "content" to "needle there")
        val result = call(tools, "workspace_grep", "query" to "needle")
        assertTrue(result.contains("one.txt:1: needle here"))
        assertTrue(result.contains("two.txt:1: needle there"))
    }

    @Test
    fun `grep with no matches returns the not-found message`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "empty.txt", "content" to "nothing relevant")
        val result = call(tools, "workspace_grep", "query" to "zzzznotfound")
        assertEquals("No workspace text matches \"zzzznotfound\".", result)
    }

    @Test
    fun `grep limit caps the total number of results returned`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "five.txt", "content" to "match1\nmatch2\nmatch3\nmatch4\nmatch5")
        val result = call(tools, "workspace_grep", "query" to "match", "limit" to "2")
        assertEquals("five.txt:1: match1\nfive.txt:2: match2", result)
    }

    @Test
    fun `grep stops mid-file at the limit without visiting a later file`() {
        val tools = WorkspaceTools(folder.root)
        // root.txt sits at depth 1 (workspace root), sub/deeper.txt at depth 2. walk()
        // (WorkspaceTools.kt:324-336) is a breadth-first traversal over a queue seeded with the
        // start directory: all depth-1 entries are enqueued, and dequeued/yielded, before any
        // depth-2 entry is enqueued (depth-2 entries are only added once their parent directory
        // is dequeued, by which point every depth-1 sibling already ahead of it in the queue has
        // been dequeued). So root.txt is guaranteed to be walked before sub/deeper.txt,
        // regardless of the underlying filesystem's directory-listing order.
        call(tools, "workspace_write", "path" to "root.txt", "content" to "match1\nmatch2\nmatch3")
        call(tools, "workspace_write", "path" to "sub/deeper.txt", "content" to "match4\nmatch5")
        val result = call(tools, "workspace_grep", "query" to "match", "limit" to "2")
        assertEquals("root.txt:1: match1\nroot.txt:2: match2", result)
    }

    @Test
    fun `grep query is case-insensitive`() {
        val tools = WorkspaceTools(folder.root)
        call(tools, "workspace_write", "path" to "shout.txt", "content" to "Hello WORLD")
        val result = call(tools, "workspace_grep", "query" to "hello")
        assertEquals("shout.txt:1: Hello WORLD", result)
    }

    @Test
    fun `grep truncates a matching line to 500 characters`() {
        val tools = WorkspaceTools(folder.root)
        val longLine = "A".repeat(600)
        call(tools, "workspace_write", "path" to "long.txt", "content" to longLine)
        val result = call(tools, "workspace_grep", "query" to "A")
        assertEquals("long.txt:1: " + "A".repeat(500), result)
    }
}
