package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun scribeWithScrollShelves(
    vararg shelves: ScrollSaver,
    imprint: Map<String, JsonElement> = emptyMap(),
    channel: Channel<Entry> = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    onSaver: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
    margins: Margin? = null,
): Scribe {
    val configuredShelves = shelves.toList()
    val configuredImprint = imprint
    val configuredMargins = margins
    return object : Scribe() {
        override val shelves: List<Saver<*>> = configuredShelves
        override val imprint: Map<String, JsonElement> = configuredImprint
        override val margins: Margin? = configuredMargins
    }.also {
        it.hire(channel = channel, onSaver = onSaver)
    }
}

internal fun scribeWithSavers(
    shelves: List<Saver<*>>,
    imprint: Map<String, JsonElement> = emptyMap(),
    margins: Margin? = null,
    channel: Channel<Entry> = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    onSaver: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
): Scribe {
    val configuredShelves = shelves
    val configuredImprint = imprint
    val configuredMargins = margins
    return object : Scribe() {
        override val shelves: List<Saver<*>> = configuredShelves
        override val imprint: Map<String, JsonElement> = configuredImprint
        override val margins: Margin? = configuredMargins
    }.also {
        it.hire(channel = channel, onSaver = onSaver)
    }
}

internal fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

internal fun createScribeInHelperAndEmit(shelf: ScrollSaver): Scribe {
    val scribe = scribeWithScrollShelves(shelf)
    scribe.newScroll(id = "scoped").seal(scribe)
    return scribe
}

internal class PaymentService {
    fun pay(orderId: String, scroll: Scroll, scribe: Scribe) {
        try {
            scroll["scrollId"] = JsonPrimitive(scroll.id)
            if (orderId == "order2") {
                throw IllegalStateException("order2 failed")
            }
            scroll["gateway"] = JsonPrimitive("stripe")
        } catch (t: Throwable) {
            scroll["error_stage"] = JsonPrimitive("gateway_call")
            scroll.seal(scribe, success = false)
            throw t
        }
    }
}

@Serializable
internal data class GatewayMeta(val retries: Int)

internal data class NonSerializableMeta(val retries: Int)

internal class RecordingShelf : ScrollSaver {
    val events = mutableListOf<SealedScroll>()
    private val writes = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun write(event: SealedScroll) {
        events += event
        writes.trySend(Unit)
    }

    suspend fun awaitEvents(count: Int) {
        repeat(count) {
            writes.receive()
        }
    }
}

internal class BlockingShelf(
    private val gate: CompletableDeferred<Unit>,
    private val firstWriteStarted: CompletableDeferred<Unit>? = null,
) : ScrollSaver {
    val events = mutableListOf<SealedScroll>()
    private val writes = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun write(event: SealedScroll) {
        firstWriteStarted?.complete(Unit)
        gate.await()
        events += event
        writes.trySend(Unit)
    }

    suspend fun awaitEvents(count: Int) {
        repeat(count) {
            writes.receive()
        }
    }
}

internal class RecordingNoteSaver : NoteSaver {
    val events = mutableListOf<Note>()
    private val writes = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun write(event: Note) {
        events += event
        writes.trySend(Unit)
    }

    suspend fun awaitEvents(count: Int) {
        repeat(count) {
            writes.receive()
        }
    }
}

internal class RecordingEntrySaver : EntrySaver {
    val events = mutableListOf<Entry>()
    private val writes = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun write(event: Entry) {
        events += event
        writes.trySend(Unit)
    }

    suspend fun awaitEvents(count: Int) {
        repeat(count) {
            writes.receive()
        }
    }
}
