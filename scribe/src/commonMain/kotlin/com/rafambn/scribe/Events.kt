package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.JsonElement

sealed interface Record

data class SealedScroll(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val context: Map<String, JsonElement>,
    val data: Map<String, JsonElement>,
): Record

data class Note(
    val tag: String,
    val message: String,
    val level: LogLevel,
    val timestamp: Long,
): Record

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class ScribeProcessConfig(
    val bufferSize: Int = 256,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
    val onSinkError: (saver: Saver<*>, record: Record, error: Throwable) -> Unit = { _, _, _ -> },
)
