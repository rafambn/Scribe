package com.rafambn.scribe

/**
 * Contract for persisting [Entry] structured logs produced by [Scribe].
 */
fun interface Archivist {
    /**
     * Handles an emitted structured log.
     */
    suspend fun write(event: Entry)
}