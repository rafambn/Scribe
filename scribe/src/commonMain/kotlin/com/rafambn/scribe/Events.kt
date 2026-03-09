package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Base type for all events emitted by [Scribe].
 */
@Serializable
sealed interface Entry

/**
 * Final representation of a scroll once it has been sealed.
 */
@Serializable
data class SealedScroll(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val context: Map<String, JsonElement>,
    val data: Map<String, JsonElement>,
): Entry

/**
 * Lightweight standalone log message emitted through [Scribe.note] or [Scribe.flingNote].
 */
@Serializable
data class Note(
    val tag: String,
    val message: String,
    val level: Urgency,
    val timestamp: Long,
): Entry

/**
 * Severity level used by [Note].
 */
@Serializable
enum class Urgency {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Controls delivery buffering and sink error handling for [Scribe].
 */
data class ScribeDeliveryConfig(
    val bufferSize: Int = 256,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
    val onSaverError: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
)
