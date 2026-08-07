package com.rafambn.scribe

import kotlinx.serialization.json.JsonElement

/** Base type for every payload delivered by a [Scribe] runtime. */
interface Entry

/** Immutable snapshot emitted when a [Scroll] is sealed. */
data class ScrollEntry(
    val data: Map<String, JsonElement>,
) : Entry, Map<String, JsonElement> by data {
    override fun toString(): String = data.toString()
}