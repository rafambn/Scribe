package com.rafambn.scribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Process-wide event writer that creates [Scroll]s and dispatches [Entry] objects to configured savers.
 */
object Scribe {
    class Inscribe {
        var shelves: List<Saver<*>> = emptyList()
        var imprint: Map<String, JsonElement> = emptyMap()
        var margins: Margin? = null
        var onIgnition: ((Throwable) -> Unit)? = null
    }

    internal var config: Inscribe? = null
    private var activeQueue: Channel<Entry>? = null
    private var processorJob: Job? = null

    /**
     * Initializes the singleton with immutable parameters.
     *
     * This function can be called again after [retire] has been called.
     */
    fun inscribe(block: Inscribe.() -> Unit) {
        val dsl = Inscribe().apply(block)
        val configuredShelves = dsl.shelves
        require(configuredShelves.isNotEmpty()) { "At least one shelf is required." }
        val onIgnition = dsl.onIgnition
        if (onIgnition != null) {
            installUncaughtExceptionHandler(onIgnition)
        }
        config = dsl
    }

    /**
     * Starts the delivery runtime using previously initialized parameters.
     *
     * The provided [channel] becomes disposable and transfers ownership to Scribe.
     * Scribe closes the channel when the processor completes or when [retire] is called.
     * Create a fresh channel for each call to this method.
     */
    fun hire(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        channel: Channel<Entry>,
        onSaver: ((saver: Saver<*>, entry: Entry, error: Throwable) -> Unit)? = null,
    ) {
        val cfg = requireConfig()
        check(activeQueue == null) { "Scribe runtime is already active. Call retire() first." }
        check(processorJob?.isActive != true) { "Scribe is still retiring. Wait for pending delivery to finish." }
        activeQueue = channel
        val createdProcessor = scope.launch {
            for (entry in channel) {
                cfg.shelves.forEach { saver ->
                    try {
                        when (saver) {
                            is EntrySaver -> saver.write(entry)
                            is ScrollSaver if entry is SealedScroll -> saver.write(entry)
                            is NoteSaver if entry is Note -> saver.write(entry)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        try {
                            onSaver?.invoke(saver, entry, e)
                        } catch (_: Throwable) {
                            // Ignore callback failures to keep delivery alive.
                        }
                    }
                }
            }
        }
        processorJob = createdProcessor
        createdProcessor.invokeOnCompletion {
            channel.close()
            if (processorJob === createdProcessor) {
                processorJob = null
            }
        }
    }

    /**
     * Creates a new scroll, optionally with a custom unique [id].
     *
     * @param id optional custom scroll id. When null, a unique id is generated.
     */
    fun newScroll(id: String? = null): Scroll {
        val cfg = requireConfig()
        val resolvedId = id ?: newScrollId()
        val scroll: Scroll = mutableMapOf()
        scroll["scroll_id"] = JsonPrimitive(resolvedId)
        cfg.imprint.forEach { (key, value) ->
            scroll[key] = value
        }
        cfg.margins?.header(scroll)
        return scroll
    }

    /**
     * Stops accepting entries, closes the delivery channel, and waits for queued events to finish delivery.
     *
     * The channel passed to [hire] is closed and must not be reused.
     * After this call completes, you may call [hire] again with a fresh channel.
     *
     * If called from within the processor coroutine (e.g., from a saver),
     * this function returns immediately without waiting to avoid deadlocks.
     */
    suspend fun retire() {
        val queue = activeQueue ?: return
        val runningProcessor = processorJob
        queue.close()
        activeQueue = null
        val callerJob = currentCoroutineContext()[Job]
        if (runningProcessor != null && !isProcessorFamily(runningProcessor, callerJob)) {
            runningProcessor.join()
        }
    }

    /**
     * Checks if the caller job is the processor itself or a descendant.
     * Traverses from caller up through parents to handle any nesting depth.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun isProcessorFamily(root: Job, target: Job?): Boolean {
        if (target == null) return false
        var current: Job? = target
        while (current != null) {
            if (current === root) return true
            current = current.parent
        }
        return false
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

    private fun requireConfig(): Inscribe =
        config ?: throw IllegalStateException("Scribe is not initialized. Call Scribe.inscribe(...) first.")

    private fun requireActiveQueue(): Channel<Entry> {
        return activeQueue ?: throw IllegalStateException("Scribe runtime is not active. Call Scribe.hire(...) first.")
    }

    internal suspend fun enqueue(entry: Entry) {
        requireActiveQueue().send(entry)
    }
}
