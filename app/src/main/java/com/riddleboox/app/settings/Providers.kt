package com.riddleboox.app.settings

/**
 * One server the diary knows by name. [baseUrl] is the server root without
 * `/v1` — the form koog's client wants (see `replyClient`), which is why the
 * settings screen offers these ready-made instead of asking anyone to type
 * them: the two well-known urls are exactly the two nobody should be able to
 * mistype.
 */
data class Provider(val label: String, val baseUrl: String)

/** The named choices on the base-url picker; anything else is "other". */
val PROVIDERS: List<Provider> = listOf(
    Provider("OpenAI", "https://api.openai.com"),
    Provider("OpenRouter", "https://openrouter.ai/api"),
)

/**
 * The provider whose canonical url this is, or null for a url set by hand.
 *
 * Exact after trimming: a variant like a trailing slash stays "other", with
 * the url in plain view on the form, rather than being renamed to a provider
 * whose canonical url would then silently replace it on save.
 */
fun providerFor(baseUrl: String): Provider? =
    PROVIDERS.firstOrNull { it.baseUrl == baseUrl.trim() }
