package com.rafambn.scribe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

typealias Scroll = MutableMap<String, JsonElement>

/** Immutable structured log emitted when a [Scroll] is sealed. */
typealias Entry = Map<String, JsonElement>

val Scroll.id: String
    get() = this["scroll_id"]?.let { (it as? JsonPrimitive)?.content } ?: error("Invalid scroll id metadata.")

@OptIn(ExperimentalUuidApi::class)
internal fun newScrollId(): String = Uuid.random().toString()

fun Scroll.seal(scribe: Scribe): Entry {
    scribe.applyFooter(this)
    val result: Entry = toMap()
    scribe.enqueue(result)
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