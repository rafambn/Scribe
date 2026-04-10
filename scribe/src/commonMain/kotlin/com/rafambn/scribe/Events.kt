package com.rafambn.scribe
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
    val success: Boolean,
    val data: Map<String, JsonElement>,
): Entry

/**
 * Lightweight standalone log message emitted through [Scribe.note].
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
