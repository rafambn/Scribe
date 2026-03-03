package com.rafambn.scribe

fun interface Saver {
    suspend fun write(event: Record)
}
