package com.riddleboox.app.history

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "com.riddleboox.app.history.conversation_store"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/**
 * Encrypts a conversation's JSON bytes at rest with a key that never leaves
 * the device's Keystore.
 *
 * Deliberately not `androidx.security.crypto.EncryptedFile`: that class picks
 * its own file name and writes its own header/metadata alongside the content,
 * which would fight [ConversationStore]'s existing atomic
 * temp-file-then-rename write (`ConversationStore.kt:86-101`). Talking to
 * `AndroidKeyStore` and `Cipher` directly keeps this class doing exactly one
 * thing — bytes in, bytes out — and leaves the file layout to the store.
 *
 * The AES-256 key is generated once, on first use, and kept in the Keystore
 * under [KEY_ALIAS]; every call after that fetches the same key by alias. A
 * fresh random IV is generated per encryption (GCM's requirement — reusing an
 * IV with the same key breaks its confidentiality guarantee) and prepended to
 * the ciphertext, since [decrypt] needs it back and nowhere else stores it.
 * The GCM authentication tag rides along inside the ciphertext produced by
 * [Cipher] itself, so a flipped bit anywhere — tampering or plain disk
 * corruption — surfaces as an `AEADBadTagException` out of [decrypt] rather
 * than silently wrong bytes.
 */
class KeystoreConversationCipher : ConversationCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val body = cipher.doFinal(plaintext)
        return cipher.iv + body
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > GCM_IV_BYTES) { "ciphertext too short to contain an IV" }
        val iv = ciphertext.copyOfRange(0, GCM_IV_BYTES)
        val body = ciphertext.copyOfRange(GCM_IV_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}
