package com.riddleboox.app.reply

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the configured server actually serves, asked over the same wire the
 * diary's replies travel: `GET <base>/v1/models` with the same bearer key.
 * The curated [VISION_MODELS] shortlist stays as the offline fallback and as
 * the source of measured [VisionModel.reasoning] defaults.
 */

/**
 * Blocking; call off the main thread. [baseUrl] is the server root without
 * `/v1`, exactly as [replyClient] takes it, so the two can never disagree
 * about which server they mean.
 */
fun fetchModelIds(baseUrl: String, apiKey: String): List<String> {
    val connection = URL(baseUrl.trim().trimEnd('/') + "/v1/models").openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("the server answered HTTP $code")
        return parseModelIds(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

/**
 * Ids out of an OpenAI-shaped `{"data":[{"id":…}]}` list, sorted for a
 * scrollable picker.
 *
 * Where the entry says what the model can see (OpenRouter's
 * `architecture.input_modalities`), models that cannot read an image are
 * dropped: this diary sends every page as a picture, and a text-only model
 * would fail on the first turn. OpenAI's list says nothing about modalities,
 * and silence keeps the model — better an id the writer must judge than an
 * empty list.
 */
fun parseModelIds(json: String): List<String> {
    val data = Json.parseToJsonElement(json).jsonObject["data"] ?: return emptyList()
    // Soft casts throughout: a self-hosted proxy writing `"architecture": null`
    // or some other odd shape must degrade to "no modality info" for that one
    // entry, not throw away the whole catalogue.
    return data.jsonArray
        .asSequence()
        .mapNotNull { it as? JsonObject }
        .filter { entry ->
            val modalities = ((entry["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            modalities == null || "image" in modalities
        }
        .mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        .distinct()
        .sorted()
        .toList()
}
