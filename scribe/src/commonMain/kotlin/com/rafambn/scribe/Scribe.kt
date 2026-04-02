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
 * Process-wide event writer that creates [Scroll]s and dispatches [Entry] objects to configured savers.
 */
object Scribe {
    private class InitConfig(
        val shelves: List<Saver<*>>,
        val imprint: Map<String, JsonElement> = emptyMap(),
        val deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        val margins: Margin? = null,
    )

    private var config: InitConfig? = null
    private var activeQueue: Channel<Entry>? = null
    private var processorJob: Job? = null
    private val scrollsById = mutableMapOf<String, Scroll>()

    /**
     * Initializes the singleton with immutable parameters.
     *
     * This function can be called only once per process lifetime.
     */
    fun init(
        shelves: List<Saver<*>>,
        imprint: Map<String, JsonElement> = emptyMap(),
        deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        margins: Margin? = null,
    ) {
        check(config == null) { "Scribe is already initialized and cannot be initialized again." }
        require(deliveryConfig.bufferSize >= -2) { "BufferSize must be >= -2. Check Channel documentation" }
        require(shelves.isNotEmpty()) { "At least one shelf is required." }
        config = InitConfig(
            shelves = shelves.toList(),
            imprint = imprint,
            deliveryConfig = deliveryConfig,
            margins = margins,
        )
    }

    /**
     * Convenience initializer for a single saver.
     */
    fun init(
        shelf: Saver<*>,
        imprint: Map<String, JsonElement> = emptyMap(),
        deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        margins: Margin? = null,
    ) {
        init(
            shelves = listOf(shelf),
            imprint = imprint,
            deliveryConfig = deliveryConfig,
            margins = margins,
        )
    }

    /**
     * Starts the delivery runtime using previously initialized parameters.
     */
    fun hire(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onIgnition: ((Throwable) -> Unit)? = null,
    ) {
        val cfg = requireConfig()
        check(activeQueue == null) { "Scribe runtime is already active. Call retire() or planRetire() first." }
        check(processorJob?.isActive != true) { "Scribe is still retiring. Wait for pending delivery to finish." }
        if (onIgnition != null) {
            installUncaughtExceptionHandler(onIgnition)
        }
        val queue = Channel<Entry>(
            capacity = cfg.deliveryConfig.bufferSize,
            onBufferOverflow = cfg.deliveryConfig.overflowStrategy,
        )
        activeQueue = queue
        val createdProcessor = scope.launch {
            for (entry in queue) {
                cfg.shelves.forEach { saver ->
                    try {
                        when (saver) {
                            is EntrySaver -> saver.write(entry)
                            is ScrollSaver if entry is SealedScroll -> saver.write(entry)
                            is NoteSaver if entry is Note -> saver.write(entry)
                        }
                    } catch (e: Throwable) {
                        cfg.deliveryConfig.onSaverError(saver, entry, e)
                    }
                }
            }
        }
        processorJob = createdProcessor
        // TODO: log the cancellation cause once internal logging is added to the library.
        createdProcessor.invokeOnCompletion {
            queue.close()
            if (processorJob === createdProcessor) {
                processorJob = null
            }
        }
    }

    /**
     * Returns all currently created scrolls.
     */
    fun seekScrolls(): List<Scroll> {
        ensureActive()
        return scrollsById.values.toList()
    }

    /**
     * Creates a new scroll, optionally with a custom unique [id].
     *
     * @param id optional custom scroll id. When null, a unique id is generated.
     */
    fun unrollScroll(id: String? = null): Scroll {
        val cfg = requireConfig()
        val queue = requireActiveQueue()
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
            imprint = cfg.imprint,
            onSeal = { cfg.margins?.footer(it) },
            onSealed = { scrollsById.remove(it.id) },
            emitSealedScroll = { queue.send(it) },
            tryEmitSealedScroll = { queue.trySend(it) },
        )
        cfg.margins?.header(scroll)
        scrollsById[resolvedId] = scroll
        return scroll
    }

    /**
     * Stops accepting new entries without waiting for pending delivery.
     */
    fun retire() {
        activeQueue?.close()
        clearActiveRuntime()
    }

    /**
     * Stops accepting entries and waits for queued events to finish delivery.
     */
    suspend fun planRetire() {
        val queue = activeQueue ?: return
        val runningProcessor = processorJob
        queue.close()
        clearActiveRuntime()
        val callerJob = currentCoroutineContext()[Job]
        if (runningProcessor != null && !isProcessorFamily(runningProcessor, callerJob)) {
            runningProcessor.join()
            if (processorJob === runningProcessor) {
                processorJob = null
            }
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
        requireActiveQueue().send(
            Note(
                tag = tag,
                message = message,
                level = level,
                timestamp = timestamp,
            ),
        )
    }

    /**
     * Emits a [Note] using non-blocking best-effort enqueue.
     *
     * @param tag logical source/category for the note.
     * @param message note text payload.
     * @param level severity level for the note.
     * @param timestamp epoch milliseconds associated with the note.
     *
     * @return `true` when the note was accepted by the queue, `false` when it was rejected.
     */
    fun flingNote(
        tag: String,
        message: String,
        level: Urgency = Urgency.INFO,
        timestamp: Long = nowEpochMs(),
    ): Boolean {
        return requireActiveQueue().trySend(
            Note(
                tag = tag,
                message = message,
                level = level,
                timestamp = timestamp,
            ),
        ).isSuccess
    }

    private fun clearActiveRuntime() {
        activeQueue = null
        scrollsById.clear()
    }

    private fun requireConfig(): InitConfig =
        config ?: throw IllegalStateException("Scribe is not initialized. Call Scribe.init(...) first.")

    private fun ensureActive() {
        if (activeQueue == null) {
            throw IllegalStateException("Scribe runtime is not active. Call Scribe.hire(...) first.")
        }
    }

    private fun requireActiveQueue(): Channel<Entry> {
        ensureActive()
        return checkNotNull(activeQueue)
    }

    private fun isProcessorFamily(root: Job, target: Job?): Boolean {
        if (target == null) return false
        if (target === root) return true
        return containsDescendant(root, target)
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
