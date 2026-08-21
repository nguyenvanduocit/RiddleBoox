package com.riddleboox.app.reply

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelsTest {

    @Test
    fun `the shortlist is short, distinct, and describes itself`() {
        assertTrue("dài quá thì không ai chọn nổi trên tablet", VISION_MODELS.size <= 10)
        assertEquals(VISION_MODELS.size, VISION_MODELS.map { it.id }.toSet().size)
        assertTrue(VISION_MODELS.all { it.label.isNotBlank() && it.note.isNotBlank() })
    }

    @Test
    fun `every id is a provider-qualified OpenRouter id`() {
        assertTrue(VISION_MODELS.all { it.id.contains("/") && it.id == it.id.trim() })
    }

    @Test
    fun `a known model is offered once, not twice`() {
        val choices = modelChoices("google/gemini-3.7-flash")

        assertEquals(VISION_MODELS.size, choices.size)
        assertEquals(1, choices.count { it.id == "google/gemini-3.7-flash" })
    }

    /**
     * A setup that works must survive a visit to the settings screen, even if
     * this list has never heard of the model running it.
     */
    @Test
    fun `a hand-set model is kept on the list`() {
        val choices = modelChoices("my-own/vision-model")

        assertEquals(VISION_MODELS.size + 1, choices.size)
        assertEquals("my-own/vision-model", choices.last().id)
    }

    @Test
    fun `surrounding spaces do not make a known model look unknown`() {
        assertEquals(VISION_MODELS.size, modelChoices("  openai/gpt-5.6-luna  ").size)
    }

    @Test
    fun `nothing configured yet just offers the shortlist`() {
        assertEquals(VISION_MODELS, modelChoices(""))
    }

    /**
     * Effort is per-model because the houses disagree about what it means:
     * Gemini cannot be told to stop thinking, and Claude is not thinking until
     * it is told to start.
     */
    @Test
    fun `only a model measured at an effort is sent one`() {
        assertEquals(ReasoningEffort.LOW, reasoningFor("openai/gpt-5.6-luna"))
        assertNull(reasoningFor("anthropic/claude-haiku-4.5"))
        assertNull(reasoningFor("qwen/qwen3-vl-32b-instruct"))
    }

    @Test
    fun `a model this list never heard of thinks as it likes`() {
        assertNull(reasoningFor("my-own/vision-model"))
    }

    @Test
    fun `no model is asked for an effort Gemini would reject`() {
        val gemini = VISION_MODELS.filter { it.id.startsWith("google/") }

        assertTrue(gemini.isNotEmpty())
        assertTrue(gemini.none { it.reasoning == ReasoningEffort.NONE })
    }
}
