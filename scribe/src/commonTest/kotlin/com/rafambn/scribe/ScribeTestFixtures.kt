package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private var activeDelegatedSavers: List<Saver<*>> = emptyList()
private var activeMargin: Margin? = null
private var onSaverErrorCallback: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> }

private val delegatingMargin = object : Margin {
    override fun header(scroll: Scroll) {
        activeMargin?.header(scroll)
    }

    override fun footer(scroll: Scroll) {
        activeMargin?.footer(scroll)
    }
}

private val delegatingEntrySaver = EntrySaver { entry ->
    activeDelegatedSavers.forEach { saver ->
        try {
            when (saver) {
                is EntrySaver -> saver.write(entry)
                is ScrollSaver if entry is SealedScroll -> saver.write(entry)
                is NoteSaver if entry is Note -> saver.write(entry)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            try {
                onSaverErrorCallback(saver, entry, error)
            } catch (_: Throwable) {
                // Keep test delivery path alive when callback fails.
            }
        }
    }
}

private var isInitialized = false

private fun ensureScribeInitialized() {
    if (isInitialized) {
        runBlocking { Scribe.retire() }
    }
    Scribe.inscribe {
        shelves = listOf(delegatingEntrySaver)
        imprint = emptyMap()
        margins = delegatingMargin
    }
    isInitialized = true
}

internal fun scribeWithScrollShelves(
    vararg shelves: ScrollSaver,
    imprint: Map<String, JsonElement> = emptyMap(),
    channel: Channel<Entry> = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    onSaver: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
    margins: Margin? = null,
): Scribe {
    ensureScribeInitialized()
    Scribe.config!!.imprint = imprint
    activeDelegatedSavers = shelves.toList()
    activeMargin = margins
    onSaverErrorCallback = onSaver
    Scribe.hire(channel = channel, onSaver = onSaver)
    return Scribe
}

internal fun scribeWithSavers(
    shelves: List<Saver<*>>,
    imprint: Map<String, JsonElement> = emptyMap(),
    margins: Margin? = null,
    channel: Channel<Entry> = Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    onSaver: (saver: Saver<*>, entry: Entry, error: Throwable) -> Unit = { _, _, _ -> },
): Scribe {
    ensureScribeInitialized()
    Scribe.config!!.imprint = imprint
    activeDelegatedSavers = shelves
    activeMargin = margins
    onSaverErrorCallback = onSaver
    Scribe.hire(channel = channel, onSaver = onSaver)
    return Scribe
}

internal fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

internal suspend fun createScribeInHelperAndEmit(shelf: ScrollSaver): Scribe {
    val scribe = scribeWithScrollShelves(shelf)
    scribe.newScroll(id = "scoped").seal()
    return scribe
}

internal class PaymentService {
    suspend fun pay(orderId: String, scroll: Scroll) {
        try {
            scroll["scrollId"] = JsonPrimitive(scroll.id)
            if (orderId == "order2") {
                throw IllegalStateException("order2 failed")
            }
            scroll["gateway"] = JsonPrimitive("stripe")
        } catch (t: Throwable) {
            scroll["error_stage"] = JsonPrimitive("gateway_call")
            scroll.seal(success = false)
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
