package com.rafambn.scribe

open class Shelf {
    open suspend fun write(event: SealedScrollEvent) = Unit
    open suspend fun writeNote(event: ScribeNoteEvent) = Unit
}
