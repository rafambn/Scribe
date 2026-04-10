package com.rafambn.scribe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
suspend fun Scroll.seal(success: Boolean = true): SealedScroll {
    Scribe.config?.margins?.footer(this)
    val result = SealedScroll(success = success, data = this)
    Scribe.enqueue(result)
    return result
}

/**
 * Copies only missing keys and values from [scroll] into this scroll.
 */
fun Scroll.extend(scroll: Scroll): Scroll {
    scroll.forEach { (key, value) ->
        if (!containsKey(key)) {
            this[key] = value
        }
    }
    return this
}

/**
 * Appends [scroll] into this scroll as a nested JSON element using [key].
 */
fun Scroll.append(key: String, scroll: Scroll): Scroll {
    this[key] = JsonObject(scroll.toMap())
    return this
}
