package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface Record

@Serializable
data class SealedScroll(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val context: Map<String, JsonElement>,
    val data: Map<String, JsonElement>,
): Record

@Serializable
data class Note(
    val tag: String,
    val message: String,
    val level: LogLevel,
    val timestamp: Long,
): Record

@Serializable
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class ScribeDeliveryConfig(
    val bufferSize: Int = 256,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
    val onSaverError: (saver: Saver<*>, record: Record, error: Throwable) -> Unit = { _, _, _ -> },
)
