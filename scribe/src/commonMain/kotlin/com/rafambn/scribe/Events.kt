package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface Entry

@Serializable
data class SealedScroll(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val context: Map<String, JsonElement>,
    val data: Map<String, JsonElement>,
): Entry

@Serializable
data class Note(
    val tag: String,
    val message: String,
    val level: Urgency,
    val timestamp: Long,
): Entry

@Serializable
enum class Urgency {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class ScribeDeliveryConfig(
    val bufferSize: Int = 256,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
    val onSaverError: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
)

enum class ScribeRetireStrategies {
    CLOSE_ONLY,
    CLOSE_AND_DRAIN,
    CANCEL_IMMEDIATELY,
}
