package com.rafambn.scribe

/**
 * Contract for persisting [Entry] instances produced by [Scribe].
 */
fun interface Saver<T : Entry> {
    /**
     * Handles an emitted event.
     */
    suspend fun write(event: T)
}

/**
 * Saver specialized for [Note] events.
 */
fun interface NoteSaver : Saver<Note>

/**
 * Saver specialized for [SealedScroll] events.
 */
fun interface ScrollSaver : Saver<SealedScroll>

/**
 * Saver that receives all entry types.
 */
fun interface EntrySaver : Saver<Entry>
