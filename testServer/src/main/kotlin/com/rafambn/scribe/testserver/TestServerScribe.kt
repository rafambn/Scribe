package com.rafambn.scribe.testserver

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.Entry
import com.rafambn.scribe.slf4j.ScribeBackend
import com.rafambn.scribe.slf4j.Slf4jScribe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.serialization.json.JsonPrimitive

@ScribeBackend
object TestServerScribe : Slf4jScribe() {
    override val archivists = listOf(
        Archivist { entry: Entry -> println(entry) },
    )
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit) =
        { _, _, error -> error.printStackTrace() }

    override val imprint = mapOf("imprint" to JsonPrimitive("imprint value"))

}
