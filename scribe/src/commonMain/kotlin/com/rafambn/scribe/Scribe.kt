package com.rafambn.scribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Independent event writer that creates [Scroll]s and dispatches [Entry] objects to configured savers.
 *
 * Create an object that extends this type and override its configuration:
 *
 * ```
 * object AppScribe : Scribe() {
 *     override val shelves = listOf<EntrySaver>(EntrySaver { entry -> println(entry) })
 * }
 * ```
 */
abstract class Scribe {
    /**
     * Savers receiving entries emitted by this instance.
     */
    protected abstract val shelves: List<Saver<*>>

    /**
     * Fields copied into every [Scroll] created by this instance.
     */
    protected open val imprint: Map<String, JsonElement> = emptyMap()

    /**
     * Optional lifecycle enrichment for scrolls created by this instance.
     */
    protected open val margins: Margin? = null

    /**
     * Optional uncaught exception callback.
     *
     * Although configured on an instance, uncaught exception handling is a
     * platform-global hook and should normally be owned by the application.
     */
    protected open val onIgnition: ((Throwable) -> Unit)? = null

    private var activeQueue: Channel<Entry>? = null
    private var processorJob: Job? = null
    private var ignitionInstalled: Boolean = false

    /**
     * Starts delivery for this runtime instance.
     *
     * The provided [channel] becomes disposable and transfers ownership to this instance.
     * This instance closes the channel when the processor completes or when [retire] is called.
     * Create a fresh channel for each call to this method.
     */
    fun hire(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        channel: Channel<Entry>,
        onSaver: ((saver: Saver<*>, entry: Entry, error: Throwable) -> Unit)? = null,
    ) {
        val configuredShelves = shelves
        require(configuredShelves.isNotEmpty()) { "At least one shelf is required." }
        check(activeQueue == null) { "Scribe runtime is already active. Call retire() first." }
        check(processorJob?.isActive != true) { "Scribe is still retiring. Wait for pending delivery to finish." }
        val exceptionHandler = onIgnition
        if (!ignitionInstalled && exceptionHandler != null) {
            installUncaughtExceptionHandler(exceptionHandler)
            ignitionInstalled = true
        }
        activeQueue = channel
        val createdProcessor = scope.launch {
            for (entry in channel) {
                configuredShelves.forEach { saver ->
                    if (saver.accepts != null && saver.accepts != entry::class) return@forEach
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (saver as Saver<Entry>).write(entry)
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
        val resolvedId = id ?: newScrollId()
        val scroll = mutableMapOf<String, JsonElement>()
        scroll["scroll_id"] = JsonPrimitive(resolvedId)
        imprint.forEach { (key, value) ->
            scroll[key] = value
        }
        margins?.header(scroll)
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
        val queue = activeQueue
        val runningProcessor = processorJob
        if (queue == null && runningProcessor == null) return
        activeQueue = null
        queue?.close()
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

    internal fun applyFooter(scroll: Scroll) {
        margins?.footer(scroll)
    }

    private fun requireActiveQueue(): Channel<Entry> {
        return activeQueue ?: throw IllegalStateException("This Scribe runtime is not active. Call hire(...) first.")
    }

    fun enqueue(entry: Entry) {
        requireActiveQueue().trySendBlocking(entry)
    }

    private fun <E> Channel<E>.trySendBlocking(element: E) {
        val result = trySend(element)
        if (result.isSuccess) return
        runBlocking {
            runCatching { send(element) }
        }
    }
}
