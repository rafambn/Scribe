package com.rafambn.scribe

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.JsonElement

data class SealedScrollEvent(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val data: Map<String, JsonElement>,
    val startedAtEpochMs: Long,
    val sealedAtEpochMs: Long,
)

enum class ScribeNoteLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class ScribeNoteEvent(
    val level: ScribeNoteLevel,
    val message: String,
    val scrollId: String?,
    val createdAtEpochMs: Long,
)

data class ScribeProcessConfig(
    val bufferSize: Int = 256,
    val overflowStrategy: BufferOverflow = BufferOverflow.DROP_OLDEST,
)
