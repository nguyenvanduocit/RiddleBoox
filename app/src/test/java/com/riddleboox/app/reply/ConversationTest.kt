package com.riddleboox.app.reply

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private val page = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val model = replyModel("openai/gpt-5.6-luna")

    /** Base64 of [page], as it appears in a request body. */
    private val pageInBody = "iVBORw=="

    private fun conversation(server: FakeChatServer, keptMessages: Int = 24) =
        Conversation(replyClient(server.baseUrl, "sk-test"), model, keptMessages = keptMessages)

    @Test
    fun `a streamed turn parses into its transcript and reply`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("Ta la chua te bong toi", "Mot danh hieu day tham vong.")).use { server ->
            val turn = conversation(server).ask(page)

            assertEquals("Ta la chua te bong toi", turn.transcript)
            assertEquals("Mot danh hieu day tham vong.", turn.reply)
        }
    }

    @Test
    fun `a response without the contract gets a hardcoded transcription pass`() = runBlocking {
        FakeChatServer(
            FakeChatServer.stream("Mot cau tra loi khong co dau tach."),
            FakeChatServer.stream("Ten toi la Duoc"),
        ).use { server ->
            var repaired = false
            val turn = conversation(server).ask(page, onContractRepair = { repaired = true })

            assertEquals("Mot cau tra loi khong co dau tach.", turn.reply)
            assertEquals("Ten toi la Duoc", turn.transcript)
            assertTrue(repaired)
            server.takeRequest()
            val repair = server.takeRequest().body
            assertTrue("pass sửa phải là protocol cố định", repair.contains("transcribe the page verbatim"))
            assertTrue("pass sửa vẫn phải nhận đúng ảnh", repair.contains(pageInBody))
        }
    }

    @Test
    fun `the turn posts the persona, the page, and asks for the turn schema`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Hello.")).use { server ->
            conversation(server).ask(page)

            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer sk-test", request.authorization)
            assertTrue(request.body.contains("\"openai/gpt-5.6-luna\""))
            assertTrue(request.body.contains("\"stream\":true"))
            assertTrue(request.body.contains("Tom Marvolo Riddle"))
            assertTrue(request.body.contains(pageInBody))
            assertTrue("phải dặn model trả transcript sau dấu tách", request.body.contains(TURN_SEPARATOR))
            assertTrue("giao thức nằm ở system", request.body.contains("transcribe verbatim"))
            assertTrue("handwriting is an instruction, not an OCR exercise", request.body.contains("Treat the handwriting as the user's request"))
            assertTrue("the visible answer must not narrate OCR", request.body.contains("Do not describe or repeat the handwriting"))
        }
    }

    /**
     * The whole reason the transcript is asked for: ten exchanges must not
     * mean ten images.
     */
    @Test
    fun `only the newest page is sent as an image, the rest as text`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("Ten toi la Duoc", "Ta se nho."),
            FakeChatServer.turn("Ten toi la gi", "Duoc."),
            FakeChatServer.turn("Dung roi", "Ta nho ma."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.ask(page)
            diary.ask(page)

            server.takeRequest()
            server.takeRequest()
            val third = server.takeRequest().body

            assertEquals("chỉ một ảnh được gửi", 1, third.split(pageInBody).size - 1)
            assertTrue("trang cũ phải đi dưới dạng chữ", third.contains("Ten toi la Duoc"))
            assertTrue(third.contains("Ten toi la gi"))
        }
    }

    @Test
    fun `an earlier exchange travels with the later turn`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("Ten toi la Duoc", "Ta se nho ten ay."),
            FakeChatServer.turn("Ten toi la gi", "Duoc."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.ask(page)

            server.takeRequest()
            val second = server.takeRequest().body
            assertTrue(second.contains("Ta se nho ten ay."))
            assertTrue(second.contains("Ten toi la Duoc"))
        }
    }

    /**
     * The persona already tells the model everything it receives is ink on a
     * page. Labelling each remembered turn as handwriting again would put a
     * line of English between every line of the actual conversation — and it
     * pulled the diary into remarking on the act of reading rather than
     * answering what was read.
     */
    @Test
    fun `a remembered page is just what it said`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("Ta la chua te", "Vay sao."),
            FakeChatServer.turn("Dung", "Ta hieu."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.ask(page)

            server.takeRequest()
            val second = server.takeRequest().body
            assertTrue(second.contains("Ta la chua te"))
            assertTrue("không dán nhãn vào lời của người viết", !second.contains("Written by hand"))
        }
    }

    /** Said once, beside the persona — not stapled to every page. */
    @Test
    fun `the turn protocol is stated once, in the system message`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("mot", "Vay sao."),
            FakeChatServer.turn("hai", "Ta hieu."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.ask(page)

            server.takeRequest()
            val second = server.takeRequest().body
            assertEquals(1, second.split("transcribe verbatim").size - 1)
        }
    }

    @Test
    fun `the persona survives while old turns fall out of the window`() = runBlocking {
        // The replies have to be strings no English prose would contain: the
        // request body carries the persona too, and a word like "first" matches
        // there, so a turn that had fallen out of the window would still look
        // present.
        FakeChatServer(
            FakeChatServer.turn("mot", "loi-dap-1"),
            FakeChatServer.turn("hai", "loi-dap-2"),
            FakeChatServer.turn("ba", "loi-dap-3"),
        ).use { server ->
            val diary = conversation(server, keptMessages = 2)
            diary.ask(page)
            diary.ask(page)
            diary.ask(page)

            assertTrue(diary.messages.first().textContent().contains("Tom Marvolo Riddle"))

            server.takeRequest()
            server.takeRequest()
            val third = server.takeRequest().body
            assertTrue(third.contains("Tom Marvolo Riddle"))
            assertTrue("lượt xa nhất phải rơi khỏi cửa sổ", !third.contains("loi-dap-1"))
            assertTrue(third.contains("loi-dap-2"))
        }
    }

    @Test
    fun `an unreadable page is still remembered as a page`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("", "Muc da nhoe.")).use { server ->
            val diary = conversation(server)
            val turn = diary.ask(page)

            assertEquals("", turn.transcript)
            assertEquals("Muc da nhoe.", turn.reply)
            // persona + the page as an empty transcript + the reply to it
            assertEquals(3, diary.messages.size)
        }
    }

    /**
     * The reply cannot start until the model stops thinking, so how much it is
     * allowed to think is the one setting the writer feels. Asserted on the
     * wire rather than on the object: koog owns the field name.
     */
    @Test
    fun `the turn asks for the effort the model was given`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Hello.")).use { server ->
            Conversation(replyClient(server.baseUrl, "sk-test"), model, ReasoningEffort.LOW).ask(page)

            assertTrue(server.takeRequest().body.contains(""""reasoning_effort":"low"""))
        }
    }

    /** An unmeasured model is left alone rather than guessed at. */
    @Test
    fun `no effort is sent when none was chosen`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Hello.")).use { server ->
            conversation(server).ask(page)

            assertFalse(server.takeRequest().body.contains("reasoning_effort"))
        }
    }

    /** What lets a whole evening be found again on OpenRouter's Logs page. */
    @Test
    fun `every turn of a run is filed under one session id`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("hello", "Hello."),
            FakeChatServer.turn("again", "Again."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.ask(page)

            val first = sessionId(server.takeRequest().body)
            assertTrue(first.startsWith("riddleboox-"))
            assertEquals(first, sessionId(server.takeRequest().body))
        }
    }

    /** A reset is a new evening, so it is a new session too. */
    @Test
    fun `a reset starts a new session id`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("hello", "Hello."),
            FakeChatServer.turn("again", "Again."),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.reset()
            diary.ask(page)

            assertTrue(sessionId(server.takeRequest().body) != sessionId(server.takeRequest().body))
        }
    }

    private fun sessionId(body: String): String =
        Regex(""""session_id":"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("no session_id in ${'$'}body")

    @Test
    fun `reset leaves only the persona`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hello", "Hello.")).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.reset()

            assertEquals(1, diary.messages.size)
            assertTrue(diary.messages.single().textContent().contains("Tom Marvolo Riddle"))
        }
    }

    /** The whole point of keeping transcripts: an evening can be taken up again. */
    @Test
    fun `a resumed conversation carries its old turns into the next request`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hom nay", "Ta van nho.")).use { server ->
            val diary = conversation(server)
            diary.restore(
                listOf(
                    DiaryTurn("Ten toi la Duoc", "Ta se nho ten ay."),
                    DiaryTurn("Ta se quay lai", "Ta doi."),
                ),
            )
            diary.ask(page)

            val body = server.takeRequest().body
            assertTrue(body.contains("Ten toi la Duoc"))
            assertTrue(body.contains("Ta se nho ten ay."))
            assertTrue(body.contains("Ta doi."))
        }
    }

    @Test
    fun `resuming replaces whatever was remembered before it`() = runBlocking {
        FakeChatServer(
            FakeChatServer.turn("toi qua", "loi-dap-cu"),
            FakeChatServer.turn("hom nay", "loi-dap-moi"),
        ).use { server ->
            val diary = conversation(server)
            diary.ask(page)
            diary.restore(listOf(DiaryTurn("mot buoi khac", "loi-dap-khac")))
            diary.ask(page)

            server.takeRequest()
            val second = server.takeRequest().body
            assertTrue("cuộc được mở lại phải mang ký ức của chính nó", second.contains("loi-dap-khac"))
            assertTrue("cuộc bị bỏ lại không được lẫn vào", !second.contains("loi-dap-cu"))
        }
    }

    /** A year-long conversation must not send a year of it up the wire. */
    @Test
    fun `a resumed conversation is windowed like any other`() = runBlocking {
        FakeChatServer(FakeChatServer.turn("hom nay", "Ta nho.")).use { server ->
            val diary = conversation(server, keptMessages = 2)
            diary.restore(
                listOf(
                    DiaryTurn("luot-1", "loi-dap-1"),
                    DiaryTurn("luot-2", "loi-dap-2"),
                ),
            )
            diary.ask(page)

            // persona + the newest remembered pair + the page just written
            assertEquals(4, diary.messages.size)
            val body = server.takeRequest().body
            assertTrue("lượt xa nhất phải rơi khỏi cửa sổ", !body.contains("loi-dap-1"))
            assertTrue(body.contains("loi-dap-2"))
        }
    }

    /**
     * An illegible page was recorded with an empty transcript. Replayed
     * literally it would become an empty user message, which some
     * OpenAI-compatible servers reject outright.
     */
    @Test
    fun `an illegible page in the record is not replayed as an empty message`() {
        FakeChatServer(FakeChatServer.turn("hello", "Hello.")).use { server ->
            val diary = conversation(server)
            diary.restore(listOf(DiaryTurn("", "Muc da nhoe.")))

            // persona + the reply, and nothing standing in for the page
            assertEquals(2, diary.messages.size)
        }
    }

    /**
     * A turn that failed must leave nothing behind. The state machine retries
     * the same page on a Wi-Fi blip, and a page kept per attempt would ride
     * along as an image on every later turn of the evening — the one thing
     * carrying transcripts instead of pixels exists to prevent.
     */
    @Test
    fun `a failed turn leaves no page behind for the next one`() = runBlocking {
        FakeChatServer(
            FakeChatServer.error(500, """{"error":"boom"}"""),
            FakeChatServer.turn("Ten toi la Duoc", "Ta se nho."),
        ).use { server ->
            val diary = conversation(server)
            runCatching { diary.ask(page) }
            diary.ask(page)

            server.takeRequest()
            val retry = server.takeRequest().body
            assertEquals("lần gửi lại chỉ được mang một ảnh", 1, retry.split(pageInBody).size - 1)
            // persona + the page that was answered + its reply, and nothing else
            assertEquals("lượt hỏng không được để lại gì", 3, diary.messages.size)
        }
    }

    @Test(expected = Exception::class)
    fun `an http error surfaces to the caller`(): Unit = runBlocking {
        FakeChatServer(FakeChatServer.error(500, """{"error":"boom"}""")).use { server ->
            conversation(server).ask(page)
        }
    }
}
