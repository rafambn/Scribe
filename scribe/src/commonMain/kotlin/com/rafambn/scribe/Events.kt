package com.rafambn.scribe

data class SealedScrollEvent(
    val scrollId: Int,
    val success: Boolean,
    val errorMessage: String?,
    val data: Map<String, Any>,
    val footnotes: List<String>,
    val startedAtEpochMs: Long,
    val sealedAtEpochMs: Long,
)
