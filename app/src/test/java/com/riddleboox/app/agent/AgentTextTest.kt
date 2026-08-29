package com.riddleboox.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTextTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun definition(
        description: String = "Một agent thử nghiệm.",
        toolIds: Set<String> = setOf(AgentCapability.LIBRARY),
        greetings: List<String> = listOf("Xin chào.", "Viết gì đó đi."),
        systemPrompt: String = "Bạn là một trợ lý hữu ích.",
    ) = AgentDefinition(
        manifest = AgentManifest(
            id = "test-agent",
            name = "Trợ lý thử",
            description = description,
            builtin = false,
            toolIds = toolIds,
            greetings = greetings,
        ),
        systemPrompt = systemPrompt,
        workspace = folder.root,
    )

    @Test
    fun `full agent renders name, description, tools, greetings and prompt in order`() {
        val text = definition().toPlainText()

        val expected = """
            Name: Trợ lý thử
            Description: Một agent thử nghiệm.
            Tools: library

            Greetings:
            - Xin chào.
            - Viết gì đó đi.

            System prompt:
            Bạn là một trợ lý hữu ích.
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `blank description is left out instead of printed as an empty line`() {
        val text = definition(description = "").toPlainText()

        assertFalse(text.contains("Description:"))
        assertTrue(text.startsWith("Name: Trợ lý thử\nTools:"))
    }

    @Test
    fun `multiple greetings print one per line in order`() {
        val text = definition(greetings = listOf("Một.", "Hai.", "Ba.")).toPlainText()

        assertTrue(text.contains("Greetings:\n- Một.\n- Hai.\n- Ba."))
    }

    @Test
    fun `empty toolIds does not crash and prints an empty tools line`() {
        // The default shape now: an agent with no opted-in capability at all.
        val manifest = AgentManifest(
            id = "bare",
            name = "Trần trụi",
            description = "",
            builtin = false,
            toolIds = emptySet(),
            greetings = listOf("Chào."),
        )
        val agent = AgentDefinition(manifest = manifest, systemPrompt = "Prompt.", workspace = folder.root)

        val text = agent.toPlainText()

        assertTrue(text.contains("Tools: \n"))
    }
}
