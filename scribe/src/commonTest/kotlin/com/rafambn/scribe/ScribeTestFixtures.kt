package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun scribeWithScrollShelves(
    vararg shelves: ScrollSaver,
    imprint: Map<String, JsonElement> = emptyMap(),
    deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
    margins: Margin? = null,
): Scribe = Scribe(
    shelves = shelves.toList(),
    imprint = imprint,
    deliveryConfig = deliveryConfig,
    margins = margins,
)

internal fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

internal suspend fun createScribeInHelperAndEmit(shelf: ScrollSaver): Scribe {
    val scribe = scribeWithScrollShelves(shelf)
    scribe.unrollScroll(id = "scoped").seal()
    return scribe
}

internal class PaymentService {
    suspend fun pay(orderId: String, scroll: Scroll) {
        try {
            scroll.writeSerializable("scrollId", scroll.id)
            if (orderId == "order2") {
                throw IllegalStateException("order2 failed")
            }
            scroll.writeSerializable("gateway", "stripe")
        } catch (t: Throwable) {
            scroll.writeSerializable("error_stage", "gateway_call")
            scroll.seal(success = false, error = t)
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
