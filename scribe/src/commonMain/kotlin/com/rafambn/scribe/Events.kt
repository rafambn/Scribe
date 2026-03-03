package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.JsonElement

sealed interface Record

data class SealedScroll(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val data: Map<String, JsonElement>,
    val startedAtEpochMs: Long,
    val sealedAtEpochMs: Long,
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
)
