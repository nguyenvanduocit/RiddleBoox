package com.riddleboox.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The three values [askDiary][com.riddleboox.app.reply.askDiary]'s koog
 * client and model are built from, as edited on the device.
 */
data class ReplySettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/**
 * What the on-screen keyboard hands back is not yet a setting: it arrives with
 * stray spaces, and a field left empty means the user cleared it rather than
 * chose it. A blank base URL or model would build a diary that can never
 * answer, so each falls back to [defaults].
 *
 * A blank API key is a real choice — MainActivity reads it as "no diary to ask" and runs the diary offline (MainActivity.kt:110-114) — so it is
 * trimmed and kept as written.
 */
fun ReplySettings.sanitized(defaults: ReplySettings): ReplySettings = ReplySettings(
    baseUrl = baseUrl.trim().ifBlank { defaults.baseUrl.trim() },
    apiKey = apiKey.trim(),
    model = model.trim().ifBlank { defaults.model.trim() },
)

private const val PREFS_FILE = "reply_settings"
private const val KEY_BASE_URL = "base_url"
private const val KEY_API_KEY = "api_key"
private const val KEY_MODEL = "model"

/**
 * Where the diary credentials live between runs. The API key is a bearer
 * token for a billed account, so this is an encrypted preferences file backed
 * by a Keystore master key rather than the plain XML `SharedPreferences`
 * writes by default.
 *
 * Deliberately knows nothing about `BuildConfig`: the caller passes the
 * build-time values in as defaults, which keeps "what the factory setting is"
 * a decision of the wiring code and this class a pure store.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Opening the file touches the Keystore, which is slow enough to be worth
     * deferring past construction — an Activity may build the store in
     * `onCreate` before it knows whether it will read.
     */
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

    /**
     * Each field falls back on its own: a user who only ever edited the model
     * keeps the build-time base URL and key.
     *
     * Sanitized before it comes back, not just on the way in from Settings:
     * the very first read, before the writer has ever opened that screen,
     * falls back to [defaultBaseUrl]/[defaultApiKey]/[defaultModel] straight
     * from `local.properties`, and nothing has trimmed those yet.
     */
    fun readOrDefault(
        defaultBaseUrl: String,
        defaultApiKey: String,
        defaultModel: String,
    ): ReplySettings {
        val defaults = ReplySettings(defaultBaseUrl, defaultApiKey, defaultModel)
        return ReplySettings(
            baseUrl = prefs.getString(KEY_BASE_URL, defaultBaseUrl) ?: defaultBaseUrl,
            apiKey = prefs.getString(KEY_API_KEY, defaultApiKey) ?: defaultApiKey,
            model = prefs.getString(KEY_MODEL, defaultModel) ?: defaultModel,
        ).sanitized(defaults)
    }

    fun write(settings: ReplySettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .apply()
    }
}
