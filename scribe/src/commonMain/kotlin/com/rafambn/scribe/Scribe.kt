package com.rafambn.scribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Independent structured log writer that creates [Scroll]s and dispatches [Entry] snapshots to configured archivists.
 *
 * Create an object that extends this type and override its configuration:
 *
 * ```
 * object AppScribe : Scribe() {
 *     override val bufferCapacity = 256
 *     override val bufferOverflow = BufferOverflow.DROP_OLDEST
 *     override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
 *     override val archivists = listOf<Archivist>(Archivist { entry -> println(entry) })
 * }
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Scribe {
    /**
     * Archivists receiving structured logs emitted by this instance.
     */
    protected open val archivists: List<Archivist> = emptyList()

    /** Maximum number of entries retained by this instance's private buffer. */
    protected open val bufferCapacity: Int = 256

    /** Overflow behavior used when this instance's private buffer is full. */
    protected open val bufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST

    /** Callback invoked when an archivist fails to write an entry. */
    protected open val onArchiveFailure: ((archivist: Archivist, entry: Entry, error: Throwable) -> Unit)? = null

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
     * It is registered on the first [hire] and unregistered during [retire].
     * Although configured on an instance, uncaught exception handling is a
     * platform-global hook and should normally be owned by the application.
     */
    protected open val onIgnition: ((Throwable) -> Unit)? = null

    private val queue: Channel<Entry> by lazy {
        Channel(
            capacity = bufferCapacity,
            onBufferOverflow = bufferOverflow,
        )
    }
    private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val intakeOpen = AtomicBoolean(true)
    private val processingEnabled = MutableStateFlow(false)
    private val retiring = AtomicBoolean(false)
    private val ignitionRegistration = AtomicReference<(() -> Unit)?>(null)
    private val retirementCompleted = CompletableDeferred<Unit>()
    private val processorJob: Job by lazy {
        ownedScope.launch {
            try {
                for (entry in queue) {
                    processingEnabled.first { it }
                    archive(archivists, entry)
                }
            } finally {
                processingEnabled.value = false
            }
        }
    }

    /** Whether new entries are currently accepted into the private buffer. */
    val isIntakeOpen: Boolean
        get() = intakeOpen.load() && !retiring.load()

    /** Whether buffered entries are currently being processed. */
    val isProcessing: Boolean
        get() = processingEnabled.value

    /**
     * Starts or resumes delivery from this instance's private buffer.
     *
     * Entries can be accepted before this method is called. Calling this method while processing
     * is already active has no effect. After [dismiss], this method resumes processing.
     */
    fun hire() {
        val configuredArchivists = archivists
        require(configuredArchivists.isNotEmpty()) { "At least one archivist is required." }
        check(!retiring.load()) { "This Scribe has been retired." }
        val exceptionHandler = onIgnition
        if (exceptionHandler != null && ignitionRegistration.load() == null) {
            val uninstall = registerGlobalIgnitionHandler(exceptionHandler)
            if (!ignitionRegistration.compareAndSet(null, uninstall)) {
                uninstall()
            }
            if (retiring.load()) {
                removeIgnitionRegistration()
                check(!retiring.load()) { "This Scribe has been retired." }
            }
        }

        val processor = processorJob
        processingEnabled.value = true
        if (processor.isCompleted) {
            processingEnabled.value = false
            error("The Scribe processor has terminated and cannot be restarted.")
        }
    }

    /** Allows new entries to be accepted into the private buffer. */
    fun openIntake() {
        check(!retiring.load()) { "This Scribe has been retired." }
        intakeOpen.store(true)
    }

    /** Stops accepting new entries without changing processing of entries already buffered. */
    fun closeIntake() {
        intakeOpen.store(false)
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
     * Cooperatively pauses delivery, preserving queued entries.
     * Intake remains independently controlled by [openIntake] and [closeIntake]. Call [hire] to
     * resume processing.
     */
    fun dismiss() {
        processingEnabled.value = false
    }

    /**
     * Permanently closes intake, drains every accepted entry, and releases the owned runtime.
     * This is the terminal lifecycle operation; neither intake nor processing can restart afterward.
     */
    suspend fun retire() {
        val callerJob = currentCoroutineContext()[Job]
        check(!isProcessorFamily(processorJob, callerJob)) {
            "retire() cannot be called from an archivist; request it from the lifecycle owner."
        }

        if (retiring.compareAndSet(expectedValue = false, newValue = true)) {
            intakeOpen.store(false)
            removeIgnitionRegistration()
            processingEnabled.value = true
            queue.close()

            val processor = processorJob
            ownedScope.launch {
                try {
                    processor.join()
                    retirementCompleted.complete(Unit)
                } catch (error: Throwable) {
                    retirementCompleted.completeExceptionally(error)
                } finally {
                    ownedScope.cancel()
                }
            }
        }

        retirementCompleted.await()
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

    internal fun enqueue(entry: Entry): Boolean {
        if (!isIntakeOpen) return false
        return queue.trySend(entry).isSuccess
    }

    private fun removeIgnitionRegistration() {
        while (true) {
            val uninstall = ignitionRegistration.load() ?: return
            if (ignitionRegistration.compareAndSet(uninstall, null)) {
                uninstall()
                return
            }
        }
    }

    private suspend fun archive(
        configuredArchivists: List<Archivist>,
        entry: Entry,
    ) = coroutineScope {
        configuredArchivists.forEach { archivist ->
            launch {
                try {
                    archivist.write(entry)
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (e: Throwable) {
                    try {
                        onArchiveFailure?.invoke(archivist, entry, e)
                    } catch (_: Throwable) {
                        // Ignore callback failures to keep delivery alive.
                    }
                }
            }
        }
    }
}
