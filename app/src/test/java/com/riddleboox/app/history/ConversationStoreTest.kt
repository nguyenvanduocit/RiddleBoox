package com.riddleboox.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A plain-Java AES/GCM cipher, standing in for [KeystoreConversationCipher]
 * in a JVM test where there is no Android Keystore to talk to. The key is
 * generated once per test with `javax.crypto.KeyGenerator` (no
 * `"AndroidKeyStore"` provider involved) and held in memory only — fine for
 * proving [ConversationStore] round-trips through *a* real cipher, wrong for
 * anything that touches production data.
 */
private class FakeAesConversationCipher : ConversationCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val iv = ciphertext.copyOfRange(0, 12)
        val body = ciphertext.copyOfRange(12, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}

class ConversationStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): ConversationStore = ConversationStore(folder.root)

    private fun turn(timestampMs: Long, transcript: String, reply: String) =
        StoredTurn(timestampMs = timestampMs, transcript = transcript, reply = reply)

    @Test
    fun `an appended turn comes back exactly as written`() {
        val store = store()
        val written = turn(1_700_000_000_000L, "Ten toi la Duoc", "Ta se nho.")

        store.appendTurn("evening-1", 1_700_000_000_000L, written)

        assertEquals(listOf(written), store.load("evening-1")?.turns)
    }

    @Test
    fun `appends keep the order they arrived in`() {
        val store = store()

        store.appendTurn("evening-1", 1L, turn(1L, "first", "one"))
        store.appendTurn("evening-1", 1L, turn(2L, "second", "two"))
        store.appendTurn("evening-1", 1L, turn(3L, "third", "three"))

        assertEquals(listOf("one", "two", "three"), store.load("evening-1")?.turns?.map { it.reply })
    }

    @Test
    fun `a conversation that was never written is absent rather than empty`() {
        assertNull(store().load("never-happened"))
        assertEquals(emptyList<StoredConversation>(), store().list())
    }

    /** The history is read newest first, whatever order the files were made in. */
    @Test
    fun `the list runs from the most recently answered backwards`() {
        val store = store()
        store.appendTurn("older", 1L, turn(1_000L, "hom qua", "…"))
        store.appendTurn("newest", 2L, turn(3_000L, "hom nay", "…"))
        store.appendTurn("middle", 3L, turn(2_000L, "toi qua", "…"))

        assertEquals(listOf("newest", "middle", "older"), store.list().map { it.id })
    }

    /** One damaged evening must not make the other fifty unreachable. */
    @Test
    fun `a damaged conversation is skipped, and the rest still list`() {
        val store = store()
        store.appendTurn("good", 1L, turn(1L, "readable", "…"))
        store.appendTurn("damaged", 2L, turn(2L, "was readable", "…"))
        store.fileFor("damaged").writeText("{ this is not json at all ][")

        assertEquals(listOf("good"), store.list().map { it.id })
        assertNull(store.load("damaged"))
    }

    @Test
    fun `a burnt conversation leaves nothing behind`() {
        val store = store()
        store.appendTurn("evening-1", 1L, turn(1L, "burn this", "…"))

        store.delete("evening-1")

        assertNull(store.load("evening-1"))
        assertEquals(emptyList<StoredConversation>(), store.list())
    }

    /** Appending is a read-modify-write, so a second one must not lose the first. */
    @Test
    fun `two conversations do not overwrite each other`() {
        val store = store()
        store.appendTurn("a", 1L, turn(1L, "in a", "…"))
        store.appendTurn("b", 2L, turn(2L, "in b", "…"))

        assertEquals(listOf("in a"), store.load("a")?.turns?.map { it.transcript })
        assertEquals(listOf("in b"), store.load("b")?.turns?.map { it.transcript })
    }

    @Test
    fun `a conversation is titled by the first line the writer wrote`() {
        val conversation = StoredConversation(
            id = "evening-1",
            startedAtMs = 1L,
            turns = listOf(
                turn(1L, "  Chao nguoi la\nva dong thu hai  ", "Chao."),
                turn(2L, "Ten toi la Duoc", "Ta se nho."),
            ),
        )

        assertEquals("Chao nguoi la", conversation.title)
    }

    /** An illegible opening page must not leave the conversation nameless. */
    @Test
    fun `a title falls through to the first legible page`() {
        val conversation = StoredConversation(
            id = "evening-1",
            startedAtMs = 1L,
            turns = listOf(
                turn(1L, "   ", "Muc nhoe."),
                turn(2L, "Con nghe duoc khong", "Nghe duoc."),
            ),
        )

        assertEquals("Con nghe duoc khong", conversation.title)
    }

    @Test
    fun `a conversation with nothing legible in it has no title`() {
        val conversation = StoredConversation("evening-1", 1L, listOf(turn(1L, "", "Muc nhoe.")))

        assertTrue(conversation.title.isEmpty())
    }

    @Test
    fun `a conversation is dated by its last answer`() {
        val store = store()
        store.appendTurn("evening-1", 1_000L, turn(2_000L, "dau tien", "…"))
        store.appendTurn("evening-1", 1_000L, turn(9_000L, "cuoi cung", "…"))

        val conversation = store.load("evening-1")!!
        assertEquals(1_000L, conversation.startedAtMs)
        assertEquals(9_000L, conversation.updatedAtMs)
    }

    // ---- a turn corrected after the fact ----

    /**
     * A streamed reply reaches disk when the stream closes, seconds before the
     * pen has drawn it. Stopping the pen by hand has to leave the conversation
     * carrying what the writer actually saw.
     */
    @Test
    fun `the last reply can be rewritten to what was actually shown`() {
        val store = ConversationStore(folder.newFolder())
        store.appendTurn("c1", 1, StoredTurn(10, "câu hỏi", "toàn bộ câu trả lời rất dài"))

        store.replaceLastReply("c1", "toàn bộ câu")

        val kept = store.load("c1")!!.turns.single()
        assertEquals("toàn bộ câu", kept.reply)
        assertEquals("câu hỏi", kept.transcript)
        assertEquals(10L, kept.timestampMs)
    }

    @Test
    fun `a reply nobody saw takes its turn out with it`() {
        val store = ConversationStore(folder.newFolder())
        store.appendTurn("c1", 1, StoredTurn(10, "câu cũ", "trả lời cũ"))
        store.appendTurn("c1", 1, StoredTurn(20, "câu mới", "chưa kịp hiện chữ nào"))

        store.replaceLastReply("c1", "")

        assertEquals(listOf("trả lời cũ"), store.load("c1")!!.turns.map { it.reply })
    }

    @Test
    fun `a conversation left with no turns at all is burned`() {
        val store = ConversationStore(folder.newFolder())
        store.appendTurn("c1", 1, StoredTurn(10, "câu duy nhất", "trả lời duy nhất"))

        store.replaceLastReply("c1", "   ")

        assertNull(store.load("c1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `rewriting a conversation that was never written changes nothing`() {
        val store = ConversationStore(folder.newFolder())
        store.replaceLastReply("khong-ton-tai", "gì đó")
        assertTrue(store.list().isEmpty())
    }

    // ---- encryption at rest ----

    /** The bytes on disk must not be the diary in the clear, whatever the file holds. */
    @Test
    fun `an encrypted conversation is not plaintext on disk`() {
        val store = ConversationStore(folder.root, cipher = FakeAesConversationCipher())
        val secret = "Ten toi la Duoc, day la nhat ky rieng tu cua toi"
        store.appendTurn("evening-1", 1L, turn(1L, secret, "Ta se nho."))

        val onDisk = store.fileFor("evening-1").readBytes()
        val onDiskText = String(onDisk, Charsets.ISO_8859_1)

        assertFalse(onDiskText.contains(secret))
    }

    /** Encryption must be transparent to the caller: what was written comes back unchanged. */
    @Test
    fun `an encrypted conversation round-trips through the same cipher`() {
        val store = ConversationStore(folder.root, cipher = FakeAesConversationCipher())
        val written = turn(1_700_000_000_000L, "Ten toi la Duoc", "Ta se nho.")

        store.appendTurn("evening-1", 1_700_000_000_000L, written)

        assertEquals(listOf(written), store.load("evening-1")?.turns)
    }
}
