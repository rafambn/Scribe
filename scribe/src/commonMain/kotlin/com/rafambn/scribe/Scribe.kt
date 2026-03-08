package com.rafambn.scribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class Scribe(
    private val scope: CoroutineScope,
    private val shelves: List<Saver<*>>,
    private val imprint: Map<String, JsonElement> = emptyMap(),
    private val deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
    private val margins: Margin? = null,
    onIgnition: ((Throwable) -> Unit)? = null,
) {
    private val scrollsById = mutableMapOf<String, Scroll>()
    internal val queue = Channel<Entry>(
        capacity = deliveryConfig.bufferSize,
        onBufferOverflow = deliveryConfig.overflowStrategy,
    )
    private val processorJob: Job

    init {
        require(deliveryConfig.bufferSize >= -2) { "BufferSize must be >= -2. Check Channel documentation" }
        require(shelves.isNotEmpty()) { "At least one shelf is required." }
        if (onIgnition != null) {
            installUncaughtExceptionHandler(onIgnition)
        }
        processorJob = scope.launch {
            for (entry in queue) {
                shelves.forEach { saver ->
                    try {
                        when (saver) {
                            is EntrySaver -> saver.write(entry)
                            is ScrollSaver if entry is SealedScroll -> saver.write(entry)
                            is NoteSaver if entry is Note -> saver.write(entry)
                        }
                    } catch (e: Throwable) {
                        deliveryConfig.onSaverError(saver, entry, e)
                    }
                }
            }
        }
        // TODO: log the cancellation cause once internal logging is added to the library.
        processorJob.invokeOnCompletion { queue.close() }
    }

    fun seekScrolls(): List<Scroll> = scrollsById.values.toList()

    fun unrollScroll(id: String? = null): Scroll {
        val resolvedId = when {
            id == null -> {
                var generatedId = newScrollId()
                while (scrollsById.containsKey(generatedId)) {
                    generatedId = newScrollId()
                }
                generatedId
            }

            scrollsById.containsKey(id) -> {
                throw IllegalArgumentException("A scroll with id '$id' already exists.")
            }

            else -> id
        }

        val scroll = Scroll(
            id = resolvedId,
            imprint = imprint.toMap(),
            onSeal = { margins?.footer(it) },
            emitSealedScroll = { queue.send(it) },
            tryEmitSealedScroll = { queue.trySend(it) },
        )
        margins?.header(scroll)
        scrollsById[resolvedId] = scroll
        return scroll
    }

    fun retire() {
        queue.close()
    }

    suspend fun planRetire() {
        queue.close()
        if (currentCoroutineContext()[Job] !== processorJob) {
            processorJob.join()
        }
    }

    suspend fun note(tag: String, message: String, level: Urgency = Urgency.INFO, timestamp: Long = nowEpochMs()) {
        queue.send(
            Note(
                tag = tag,
                message = message,
                level = level,
                timestamp = timestamp,
            ),
        )
    }

    // TODO: review silent-drop behavior after retire() — trySend result is intentionally ignored
    //       (best-effort semantics), but callers have no signal that the entry was discarded.
    fun flingNote(tag: String, message: String, level: Urgency = Urgency.INFO, timestamp: Long = nowEpochMs()) {
        queue.trySend(
            Note(
                tag = tag,
                message = message,
                level = level,
                timestamp = timestamp,
            ),
        )
    }
}
