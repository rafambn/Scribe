package com.rafambn.scribe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mutable event context represented as a map and enriched with lifecycle extension functions.
 */
typealias Scroll = MutableMap<String, JsonElement>

/**
 * Stable unique identifier for this scroll.
 */
val Scroll.id: String
    get() = this["scroll_id"]?.let { (it as? JsonPrimitive)?.content } ?: error("Invalid scroll id metadata.")

/**
 * Seals this scroll and suspends until its [SealedScroll] is enqueued.
 */
suspend fun Scroll.seal(success: Boolean = true, error: Throwable? = null): SealedScroll {
    Scribe.config?.margins?.footer(this)
    val result = SealedScroll(
        success = success,
        errorMessage = error?.message,
        data = this,
    )
    Scribe.enqueue(result)
    return result
}