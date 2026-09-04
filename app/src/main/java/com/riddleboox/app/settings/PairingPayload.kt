package com.riddleboox.app.settings

/** What a phone's pairing form adds up to — see [parsePairing]. */
data class PairingPayload(val baseUrl: String, val apiKey: String, val model: String)

/** The result of validating a submitted pairing form. */
sealed interface PairingParse {
    data class Ok(val payload: PairingPayload) : PairingParse
    /** [reason] is shown to the phone, on [rejectedPage] — plain words, no field names. */
    data class Rejected(val reason: String) : PairingParse
}

/**
 * Turns the phone's submitted form fields into a [PairingPayload] — the same
 * checks [SettingsActivity] applies by hand: the provider resolves through
 * [PROVIDERS] by label, "other" needs its own url typed in, and the key
 * can't be blank. [form] keys match the field `name`s in [pairingPage].
 */
fun parsePairing(form: Map<String, String>): PairingParse {
    val providerLabel = form["provider"]?.trim().orEmpty()
    val baseUrl = when {
        providerLabel == "other" -> form["base_url"]?.trim().orEmpty()
        else -> PROVIDERS.firstOrNull { it.label == providerLabel }?.baseUrl.orEmpty()
    }
    if (baseUrl.isEmpty()) {
        return PairingParse.Rejected("choose a server, or pick \"other\" and type one in")
    }
    val apiKey = form["api_key"]?.trim().orEmpty()
    if (apiKey.isEmpty()) return PairingParse.Rejected("paste an API key")
    val model = form["model"]?.trim().orEmpty()
    return PairingParse.Ok(PairingPayload(baseUrl, apiKey, model))
}
