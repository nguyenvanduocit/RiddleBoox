package com.riddleboox.app.reply

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory pass beside the ordinary turn: same client, same tool loop, and
 * one deliberate difference — [Conversation.memorize] must leave the history
 * exactly as it found it, because a housekeeping exchange remembered as a turn
 * would color every reply after it.
 */
class ConversationMemorizeTest {

    private val page = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val model = replyModel("openai/gpt-5.6-luna")

    private fun conversation(server: FakeChatServer, toolbox: Toolbox? = null) =
        Conversation(replyClient(server.baseUrl, "sk-test"), model, toolbox = toolbox)

    @Test
    fun `the note goes up with the whole conversation behind it`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("Ten toi la Duoc", "Ta se nho."),
            FakeChatServer.stream("Đã ghi nhớ."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.memorize("NOTE-VE-BOOKKEEPING")

            server.takeRequest()
            val pass = server.takeRequest().body
            assertTrue("the page already answered rides along as words", pass.contains("Ten toi la Duoc"))
            assertTrue(pass.contains("Ta se nho."))
            assertTrue("the note itself is the user message", pass.contains("NOTE-VE-BOOKKEEPING"))
        }
    }

    /** A model that emits the turn separator anyway loses only what follows it. */
    @Test
    fun `what the pass said comes back, without any transcript it invents`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("chuyện bịa ra", "Ta đã ghi nhớ.")).use { server ->
            val line = conversation(server).memorize("note")

            assertEquals("Ta đã ghi nhớ.", line)
        }
    }

    @Test
    fun `a memory pass leaves the conversation untouched`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("Ten toi la Duoc", "Ta se nho."),
            FakeChatServer.stream("DA-GHI-NHO-XONG"),
            FakeChatServer.turn("Ten toi la gi", "Duoc."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.memorize("NOTE-VE-BOOKKEEPING")
            diary.ask(page)

            server.takeRequest()
            server.takeRequest()
            val third = server.takeRequest().body
            assertTrue("the real turn is still remembered", third.contains("Ten toi la Duoc"))
            assertFalse("the note never becomes a turn", third.contains("NOTE-VE-BOOKKEEPING"))
            assertFalse("neither does what the pass answered", third.contains("DA-GHI-NHO-XONG"))
        }
    }

    @Test
    fun `the tools reach the pass's request`() = runBlocking {
        val box = object : Toolbox {
            override val tools: List<ToolDescriptor> = listOf(
                ToolDescriptor(
                    name = "remember",
                    description = "Keep one durable fact.",
                    requiredParameters = emptyList(),
                    optionalParameters = emptyList(),
                ),
            )
            override suspend fun call(name: String, arguments: JsonObject): String = "Remembered."
            override fun note(name: String, arguments: JsonObject): String = "committing this to memory…"
        }
        FakeChatServer(FakeChatServer.stream("Đã ghi.")).use { server ->
            conversation(server, toolbox = box).memorize("note")

            val body = server.takeRequest().body
            assertTrue("the pass must be able to write what it decides to keep", body.contains("\"tools\":[{"))
            assertTrue(body.contains("remember"))
        }
    }
}
