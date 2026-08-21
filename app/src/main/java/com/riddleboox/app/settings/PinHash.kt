package com.riddleboox.app.settings

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The pure half of the PIN lock: turning a typed PIN into bytes worth storing,
 * and telling a stored hash apart from a wrong guess. No `android.*` import —
 * unlike [PinStore], which needs a `Context` to reach the Keystore, this is
 * plain `java.security` and runs on the JVM unit test runner untouched.
 *
 * The PIN itself is never stored, only `SHA-256(salt + pin)`: a device
 * backup or a bug that leaks the prefs file exposes a hash, not the digits a
 * writer reuses on other locks. The salt makes two writers who pick the same
 * PIN land on different hashes, and rules out a precomputed table of every
 * 4-to-8-digit hash.
 */
object PinHash {

    private const val SALT_BYTES = 16

    /** A fresh salt for a newly-set PIN — never reused across [PinStore.set] calls. */
    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))

    /**
     * Whether [pin], hashed with [salt], matches [expectedHash].
     *
     * Compares with [MessageDigest.isEqual] rather than `contentEquals` or
     * `==`: those short-circuit on the first differing byte, so how long the
     * comparison takes leaks how many leading bytes a guess got right. The
     * risk is low for a PIN checked locally with no network round-trip to
     * time, but the constant-time compare costs nothing to use correctly.
     */
    fun matches(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expectedHash)
}
