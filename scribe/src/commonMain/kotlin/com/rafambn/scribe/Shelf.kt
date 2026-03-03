package com.rafambn.scribe

fun interface Saver<T : Record> {
    suspend fun write(event: T)
}

fun interface NoteSaver : Saver<Note>

fun interface ScrollSaver : Saver<SealedScroll>

fun interface RecordSaver : Saver<Record>