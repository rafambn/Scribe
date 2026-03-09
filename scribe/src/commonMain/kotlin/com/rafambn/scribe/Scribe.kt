package com.rafambn.scribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Central event writer that creates [Scroll]s and dispatches [Entry] objects to configured savers.
 *
 * @param shelves sinks that receive emitted entries.
 * @param imprint immutable key/value context copied into each created scroll.
 * @param deliveryConfig buffering, overflow, and saver error-handling settings.
 * @param margins optional hooks called on scroll start and seal.
 * @param scope coroutine scope used to run the internal delivery processor.
 * @param onIgnition optional uncaught-exception callback installed globally per platform.
 */
class Scribe(
    private val shelves: List<Saver<*>>,
    private val imprint: Map<String, JsonElement> = emptyMap(),
    private val deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
    private val margins: Margin? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    onIgnition: ((Throwable) -> Unit)? = null,
) {
    private val scrollsById = mutableMapOf<String, Scroll>()
    internal val queue = Channel<Entry>(
        capacity = deliveryConfig.bufferSize,
        onBufferOverflow = deliveryConfig.overflowStrategy,
    )
    private val processorJob: Job

    /**
     * Convenience constructor for a single saver.
     *
     * @param shelf sink that receives emitted entries.
     * @param imprint immutable key/value context copied into each created scroll.
     * @param deliveryConfig buffering, overflow, and saver error-handling settings.
     * @param margins optional hooks called on scroll start and seal.
     * @param scope coroutine scope used to run the internal delivery processor.
     * @param onIgnition optional uncaught-exception callback installed globally per platform.
     */
    constructor(
        shelf: Saver<*>,
        imprint: Map<String, JsonElement> = emptyMap(),
        deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        margins: Margin? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onIgnition: ((Throwable) -> Unit)? = null,
    ) : this(
        shelves = listOf(shelf),
        imprint = imprint,
        deliveryConfig = deliveryConfig,
        margins = margins,
        scope = scope,
        onIgnition = onIgnition,
    )

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

    /**
     * Returns all currently created scrolls.
     */
    fun seekScrolls(): List<Scroll> = scrollsById.values.toList()

    /**
     * Creates a new scroll, optionally with a custom unique [id].
     *
     * @param id optional custom scroll id. When null, a unique id is generated.
     */
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
            onSealed = { scrollsById.remove(it.id) },
            emitSealedScroll = { queue.send(it) },
            tryEmitSealedScroll = { queue.trySend(it) },
        )
        margins?.header(scroll)
        scrollsById[resolvedId] = scroll
        return scroll
    }

    /**
     * Stops accepting new entries without waiting for pending delivery.
     */
    fun retire() {
        queue.close()
    }

    /**
     * Stops accepting entries and waits for queued events to finish delivery.
     */
    suspend fun planRetire() {
        queue.close()
        val callerJob = currentCoroutineContext()[Job]
        if (!isProcessorFamily(callerJob)) {
            processorJob.join()
        }
    }

    /**
     * Emits a [Note] and suspends until it is enqueued.
     *
     * @param tag logical source/category for the note.
     * @param message note text payload.
     * @param level severity level for the note.
     * @param timestamp epoch milliseconds associated with the note.
     */
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
    /**
     * Emits a [Note] using non-blocking best-effort enqueue.
     *
     * @param tag logical source/category for the note.
     * @param message note text payload.
     * @param level severity level for the note.
     * @param timestamp epoch milliseconds associated with the note.
     */
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

    private fun isProcessorFamily(job: Job?): Boolean {
        if (job == null) return false
        if (job === processorJob) return true
        return containsDescendant(processorJob, job)
    }

    private fun containsDescendant(root: Job, target: Job): Boolean {
        val queue = ArrayDeque<Job>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.children.forEach { child ->
                if (child === target) return true
                queue.addLast(child)
            }
        }
        return false
    }
}
