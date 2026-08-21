package com.riddleboox.app.history

/**
 * How [ConversationStore] turns a conversation's JSON bytes into what actually
 * touches disk, and back.
 *
 * Kept as an interface — rather than [ConversationStore] calling the Android
 * Keystore directly — because the store's own tests run on the plain JVM
 * (`TemporaryFolder`, no `Context`), and the Keystore only exists on a real
 * device. Tests inject [NoOpConversationCipher] or a fake; app code gets
 * `KeystoreConversationCipher`.
 */
interface ConversationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * The identity cipher: bytes out exactly as they went in.
 *
 * Default for the `File`-based [ConversationStore] constructor, so the JVM
 * unit tests that build a store straight from a [java.io.File] keep reading
 * and writing plain JSON without naming a cipher at all.
 */
object NoOpConversationCipher : ConversationCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
}
