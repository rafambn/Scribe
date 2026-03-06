package com.rafambn.scribe

fun interface Saver<T : Entry> {
    suspend fun write(event: T)
}

fun interface NoteSaver : Saver<Note>

fun interface ScrollSaver : Saver<SealedScroll>

fun interface EntrySaver : Saver<Entry>