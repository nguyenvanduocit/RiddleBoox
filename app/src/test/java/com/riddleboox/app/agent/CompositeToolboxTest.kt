package com.riddleboox.app.agent

import ai.koog.agents.core.tools.ToolDescriptor
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompositeToolbox] only routes and validates uniqueness; the actual work is
 * each member [Toolbox]'s. [FakeToolbox] below stands in for a real one so
 * these tests can tell "the right box answered" from "some box answered".
 */
class CompositeToolboxTest {

    private val noArgs = JsonObject(emptyMap())

    private fun call(box: Toolbox, name: String): String = runBlocking { box.call(name, noArgs) }

    @Test
    fun `a call for the first box's tool reaches the first box`() {
        val first = FakeToolbox("search_library", response = "found in shelf")
        val second = FakeToolbox("workspace_read", response = "found in workspace")
        val composite = CompositeToolbox(listOf(first, second))

        assertEquals("found in shelf", call(composite, "search_library"))
    }

    @Test
    fun `a call for the second box's tool reaches the second box`() {
        val first = FakeToolbox("search_library", response = "found in shelf")
        val second = FakeToolbox("workspace_read", response = "found in workspace")
        val composite = CompositeToolbox(listOf(first, second))

        assertEquals("found in workspace", call(composite, "workspace_read"))
    }

    @Test
    fun `a call for a tool no box owns falls back rather than crashing`() {
        val composite = CompositeToolbox(listOf(FakeToolbox("search_library", response = "found in shelf")))

        assertEquals("There is nothing called ghost_tool to consult.", call(composite, "ghost_tool"))
    }

    @Test
    fun `note routes to the owning box, and falls back for a tool no box owns`() {
        val first = FakeToolbox("search_library", note = "đang tìm sách…")
        val second = FakeToolbox("workspace_read", note = "đang đọc…")
        val composite = CompositeToolbox(listOf(first, second))

        assertEquals("đang tìm sách…", composite.note("search_library", noArgs))
        assertEquals("đang đọc…", composite.note("workspace_read", noArgs))
        assertEquals("đang tra cứu…", composite.note("ghost_tool", noArgs))
    }

    @Test
    fun `tools gathers every box's descriptors, box order then each box's own order`() {
        val first = FakeToolbox("a", "b")
        val second = FakeToolbox("c")
        val composite = CompositeToolbox(listOf(first, second))

        assertEquals(listOf("a", "b", "c"), composite.tools.map { it.name })
    }

    @Test
    fun `two boxes declaring the same tool name fail construction`() {
        val first = FakeToolbox("search_library", response = "shelf")
        val second = FakeToolbox("search_library", response = "duplicate")

        assertThrows(IllegalStateException::class.java) {
            CompositeToolbox(listOf(first, second))
        }
    }

    @Test
    fun `an empty list of boxes has no tools and only ever falls back`() {
        val composite = CompositeToolbox(emptyList())

        assertTrue(composite.tools.isEmpty())
        assertEquals("There is nothing called anything to consult.", call(composite, "anything"))
        assertEquals("đang tra cứu…", composite.note("anything", noArgs))
    }
}

/** A toolbox that answers instantly and reports which name it owns. */
private class FakeToolbox(
    vararg names: String,
    private val response: String = "",
    private val note: String = "đang tra cứu…",
) : Toolbox {

    override val tools: List<ToolDescriptor> = names.map { name ->
        ToolDescriptor(
            name = name,
            description = "fake tool for routing tests",
            requiredParameters = emptyList(),
            optionalParameters = emptyList(),
        )
    }

    override suspend fun call(name: String, arguments: JsonObject): String = response

    override fun note(name: String, arguments: JsonObject): String = note
}
