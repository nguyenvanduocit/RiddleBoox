package com.riddleboox.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A string argument, however the model chose to send it. */
internal fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/**
 * A whole-number argument, clamped to [range].
 *
 * Read out of the primitive's text rather than as a number because a model
 * asking for five books will as readily send `5` as `"5"`, and the difference
 * is not worth a failed lookup. Unclamped by default; callers whose argument
 * has a meaningful upper or lower bound pass [range] explicitly.
 */
internal fun JsonObject.count(name: String, fallback: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int =
    (text(name).toIntOrNull() ?: fallback).coerceIn(range)
