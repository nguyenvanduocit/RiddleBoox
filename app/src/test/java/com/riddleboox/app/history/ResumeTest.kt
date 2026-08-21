package com.riddleboox.app.history

import com.riddleboox.app.reply.Conversation
import com.riddleboox.app.reply.FakeChatServer
import com.riddleboox.app.reply.replyClient
import com.riddleboox.app.reply.replyModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole point of the history, end to end: an evening written to disk,
 * found again, and carried into a request as if it had never stopped.
 *
 * The two halves are tested apart — [ConversationStoreTest] that a turn comes
 * back off disk as it went on, [com.riddleboox.app.reply.ConversationTest] that
 * restored turns reach the wire — and this joins them, because the seam between
 * them is where a resumed conversation would silently come back empty.
 */
class ResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val page = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun `an evening closed on disk is still the diary's memory days later`() = runBlocking {
        val store = ConversationStore(folder.root)
        store.appendTurn("evening-1", 1_000L, StoredTurn(1_100L, "Ten toi la Duoc", "Ta se nho ten ay."))
        store.appendTurn("evening-1", 1_000L, StoredTurn(1_200L, "Ta phai di day", "Ta doi."))

        // A new app run: nothing in memory, only what is on disk.
        val found = store.list().single()

        FakeChatServer(FakeChatServer.turn("Ta quay lai roi", "Ta biet ten nguoi.")).use { server ->
            val diary = Conversation(
                replyClient(server.baseUrl, "sk-test"),
                replyModel("openai/gpt-5.6-luna"),
            )
            diary.restore(found.toTurns())
            diary.ask(page)

            val body = server.takeRequest().body
            assertTrue("cuộc cũ phải đi cùng lượt mới", body.contains("Ten toi la Duoc"))
            assertTrue(body.contains("Ta se nho ten ay."))
            assertTrue(body.contains("Ta doi."))
        }
    }

    /** What the writer picks in the history is what the diary takes up. */
    @Test
    fun `resuming one evening does not drag the others in`() = runBlocking {
        val store = ConversationStore(folder.root)
        store.appendTurn("evening-1", 1_000L, StoredTurn(1_100L, "buoi mot", "loi-dap-mot"))
        store.appendTurn("evening-2", 2_000L, StoredTurn(2_100L, "buoi hai", "loi-dap-hai"))

        val chosen = store.load("evening-1")!!

        FakeChatServer(FakeChatServer.turn("tiep tuc", "Ta nho.")).use { server ->
            val diary = Conversation(
                replyClient(server.baseUrl, "sk-test"),
                replyModel("openai/gpt-5.6-luna"),
            )
            diary.restore(chosen.toTurns())
            diary.ask(page)

            val body = server.takeRequest().body
            assertTrue(body.contains("loi-dap-mot"))
            assertTrue("buổi khác không được lẫn vào", !body.contains("loi-dap-hai"))
        }
    }
}
