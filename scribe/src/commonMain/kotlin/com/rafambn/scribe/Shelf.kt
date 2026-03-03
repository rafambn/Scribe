package com.rafambn.scribe

fun interface Saver<T : Record> {
    suspend fun write(event: T)
}

class NoteSaver(val saver: Saver<Note>) : Saver<Note> by saver
class ScrollSaver(val saver: Saver<SealedScroll>) : Saver<SealedScroll> by saver
class RecordSaver(val saver: Saver<Record>) : Saver<Record> by saver