package com.rafambn.scribe

import kotlinx.serialization.json.JsonElement

data class SealedScrollEvent(
    val scrollId: String,
    val success: Boolean,
    val errorMessage: String?,
    val data: Map<String, JsonElement>,
    val footnotes: List<String>,
    val startedAtEpochMs: Long,
    val sealedAtEpochMs: Long,
)
