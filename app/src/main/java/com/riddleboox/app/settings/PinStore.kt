package com.riddleboox.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE = "pin_lock"
private const val KEY_SALT = "salt"
private const val KEY_HASH = "hash"

/**
 * Where the diary's PIN lives between runs — a salted hash, never the PIN
 * itself (see [PinHash] for why), in its own Keystore-backed file rather than
 * [SettingsStore]'s `reply_settings`: a future lock screen has no business
 * opening the same file as the diary's API key just to check a PIN.
 *
 * Hashing and comparison are [PinHash]'s job, which needs no `Context` and
 * runs on the plain JVM; this class only owns where the salt and hash are
 * stored, same Keystore-backed `EncryptedSharedPreferences` pattern as
 * [SettingsStore] (`SettingsStore.kt:48-91`). Cycle 3A only: this store has
 * no caller yet — the lock screen that reads [verify] on app start is a
 * later cycle's job, out of scope here.
 */
class PinStore(context: Context) {

    private val appContext = context.applicationContext

    /** Deferred past construction for the same reason as [SettingsStore.prefs]: opening it touches the Keystore. */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isSet(): Boolean = prefs.contains(KEY_HASH)

    /** Replaces whatever PIN was set before, if any — always with a fresh [PinHash.randomSalt]. */
    fun set(pin: String) {
        val salt = PinHash.randomSalt()
        val hash = PinHash.hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    /** False both for a wrong PIN and for no PIN having been set at all. */
    fun verify(pin: String): Boolean {
        val saltEncoded = prefs.getString(KEY_SALT, null) ?: return false
        val hashEncoded = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashEncoded, Base64.NO_WRAP)
        return PinHash.matches(pin, salt, expectedHash)
    }

    fun clear() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }
}
