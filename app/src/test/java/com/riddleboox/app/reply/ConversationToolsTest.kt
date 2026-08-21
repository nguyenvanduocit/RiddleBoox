package com.riddleboox.app.reply

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn loop when the diary can look something up.
 *
 * Every assertion here is about the bytes on the wire rather than about koog:
 * the one thing that cannot be read off the API is whether a field survives
 * into the request, and it is exactly the field koog drops in silence when a
 * capability is missing.
 */
class ConversationToolsTest {

    private val page = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val model = replyModel("openai/gpt-5.6-luna")

    private fun conversation(server: FakeChatServer, toolbox: Toolbox?, maxLookups: Int = 3, recentMemories: String = "") =
        Conversation(
            client = replyClient(server.baseUrl, "sk-test"),
            model = model,
            toolbox = toolbox,
            maxLookups = maxLookups,
            recentMemories = recentMemories,
        )

    @Test
    fun `the tools reach the request`() = runBlocking {
        val shelf = FakeToolbox()
        FakeChatServer(FakeChatServer.turn("sach nao", "Ba cuốn.")).use { server ->
            conversation(server, shelf).ask(page)

            val body = server.takeRequest().body
            assertTrue("koog phải gửi tools", body.contains("\"tools\":[{"))
            assertTrue(body.contains("search_library"))
            assertTrue("mô tả tham số phải đi kèm", body.contains("Words from a title"))
        }
    }

    /**
     * No toolbox, nothing said about keeping memories — a diary told it can
     * remember things on purpose when it has nowhere to put them would promise
     * and then have nothing to call.
     */
    @Test
    fun `a diary with nothing to consult is told nothing about memory`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Vay sao.")).use { server ->
            conversation(server, toolbox = null).ask(page)

            val body = server.takeRequest().body
            // koog serialises the field either way, so "no tools" is an empty
            // array rather than a missing key — and an empty array is what the
            // request already carried before any of this existed, which is why
            // declaring the capability changes nothing for a diary without one.
            assertTrue(body.contains("\"tools\":[]"))
            assertFalse(body.contains("worth remembering past this evening"))
        }
    }

    /**
     * Unlike tool-specific guidance (what a shelf holds, say — now the
     * concern of that agent's own system.md), keeping memory is something
     * every agent with any toolbox at all is told about.
     */
    @Test
    fun `a diary with any toolbox is told about the memory it keeps`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Vay sao.")).use { server ->
            conversation(server, FakeToolbox()).ask(page)

            val body = server.takeRequest().body
            assertTrue(body.contains("worth remembering past this evening"))
        }
    }

    /**
     * The whole point of [Conversation.recentMemories]: it is not something
     * the model has to decide to go fetch with a tool call, it is just
     * already in the system message.
     */
    @Test
    fun `recent memories are folded straight into the system message`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Vay sao.")).use { server ->
            conversation(server, FakeToolbox(), recentMemories = "- Thích trà xanh, không thích cà phê.").ask(page)

            val body = server.takeRequest().body
            assertTrue(body.contains("Thích trà xanh, không thích cà phê."))
        }
    }

    /** Nothing remembered yet is not worth a paragraph saying so. */
    @Test
    fun `no already-remembered preface when there is nothing to show`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Vay sao.")).use { server ->
            conversation(server, FakeToolbox()).ask(page)

            val body = server.takeRequest().body
            assertFalse(body.contains("Already remembered"))
        }
    }

    @Test
    fun `a lookup is run and its answer comes back in the next request`() = runBlocking {
        val shelf = FakeToolbox(answer = "1 book: Câu Chuyện Nghệ Thuật | E. H. Gombrich")
        FakeChatServer(
            lookup("call_1", "search_library", """{"query":"gombrich"}"""),
            FakeChatServer.turn("sach ve nghe thuat", "Cuốn của Gombrich."),
        ).use { server ->
            val looked = ArrayList<String>()
            val notes = ArrayList<String>()
            val turn = conversation(server, shelf).ask(
                page,
                onLookup = { name, note -> looked.add(name); notes.add(note) },
            )

            assertEquals("Cuốn của Gombrich.", turn.reply)
            assertEquals(listOf("search_library"), looked)
            assertEquals("trang phải nói đang tra cái gì", listOf("đang tra search_library…"), notes)
            assertEquals(listOf("search_library"), shelf.calls.map { it.first })
            assertEquals("gombrich", shelf.calls.single().second["query"].toString().trim('"'))

            server.takeRequest()
            val second = server.takeRequest().body
            assertTrue("cuộc gọi tool phải quay lại như lời của assistant", second.contains("tool_calls"))
            assertTrue(second.contains("call_1"))
            assertTrue("kết quả tra cứu phải có mặt", second.contains("Gombrich"))
        }
    }

    /**
     * A chapter read to answer one line must not be paid for again on every
     * line after it. What the diary keeps of a turn is the page and its answer.
     */
    @Test
    fun `what was looked up is not remembered`() = runBlocking {
        val shelf = FakeToolbox(answer = "MOT-DOAN-SACH-RAT-DAI")
        FakeChatServer(
            lookup("call_1", "search_library", """{"query":"x"}"""),
            FakeChatServer.turn("trang mot", "Ta biet."),
            FakeChatServer.turn("trang hai", "Ta nho."),
        ).use { server ->
            val diary = conversation(server, shelf)
            diary.ask(page)
            diary.ask(page)

            server.takeRequest()
            server.takeRequest()
            val third = server.takeRequest().body
            assertFalse("nội dung tra cứu không được sống sang lượt sau", third.contains("MOT-DOAN-SACH-RAT-DAI"))
            assertFalse(third.contains("tool_calls"))
            assertTrue("nhưng lượt trước thì vẫn nhớ", third.contains("Ta biet."))
        }
    }

    /** Withholding the tools is the brake: a round with none cannot ask again. */
    @Test
    fun `looking has an end`() = runBlocking {
        val shelf = FakeToolbox()
        FakeChatServer(
            lookup("call_1", "search_library", """{"query":"a"}"""),
            lookup("call_2", "search_library", """{"query":"b"}"""),
            FakeChatServer.turn("thoi du roi", "Ta khong tim nua."),
        ).use { server ->
            val turn = conversation(server, shelf, maxLookups = 2).ask(page)

            assertEquals("Ta khong tim nua.", turn.reply)
            assertEquals(2, shelf.calls.size)
            server.takeRequest()
            server.takeRequest()
            val third = server.takeRequest().body
            assertTrue("lượt cuối không được mời tra cứu nữa", third.contains("\"tools\":[]"))
        }
    }

    /**
     * Ink cannot be lifted. Whatever the model wrote before it decided to look
     * something up is already on the page, so it stays part of the reply.
     */
    @Test
    fun `words written before a lookup stay in the reply`() = runBlocking {
        val shelf = FakeToolbox()
        FakeChatServer(
            lookup("call_1", "search_library", """{"query":"a"}""", said = "Chờ đã. "),
            FakeChatServer.turn("gi vay", "Cuốn ấy ta biết."),
        ).use { server ->
            val written = StringBuilder()
            val turn = conversation(server, shelf).ask(page, onReplyText = { written.append(it) })

            assertEquals("Chờ đã. Cuốn ấy ta biết.", turn.reply)
            assertEquals("Chờ đã. Cuốn ấy ta biết.", written.toString())
        }
    }
}

/** A shelf that answers instantly and records what it was asked. */
private class FakeToolbox(private val answer: String = "nothing there") : Toolbox {

    val calls = ArrayList<Pair<String, JsonObject>>()

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "search_library",
            description = "The books on the writer's e-reader.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("query", "Words from a title or an author.", ToolParameterType.String),
            ),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String {
        calls.add(name to arguments)
        return answer
    }

    override fun note(name: String, arguments: JsonObject): String = "đang tra $name…"
}

/**
 * A streamed response that asks for one tool call, optionally after [said]
 * words of ordinary reply — the awkward shape a real model produces when it
 * starts answering and then thinks better of it.
 */
private fun lookup(id: String, tool: String, arguments: String, said: String = ""): String = buildString {
    append("HTTP/1.1 200 OK\nContent-Type: text/event-stream\nConnection: close\n\n")
    if (said.isNotEmpty()) append("data: ${chunk("\"content\":\"${escape(said)}\"")}\n\n")
    val call = "\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"type\":\"function\"," +
        "\"function\":{\"name\":\"$tool\",\"arguments\":\"${escape(arguments)}\"}}]"
    append("data: ${chunk(call)}\n\n")
    append("data: ${chunk("", finish = "tool_calls")}\n\n")
    append("data: [DONE]\n\n")
}

private fun chunk(delta: String, finish: String? = null): String =
    "{\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fake-model\"," +
        "\"choices\":[{\"index\":0,\"delta\":{$delta}," +
        "\"finish_reason\":${if (finish == null) "null" else "\"$finish\""}}]}"

private fun escape(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
