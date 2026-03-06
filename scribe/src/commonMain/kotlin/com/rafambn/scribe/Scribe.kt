package com.rafambn.scribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

class Scribe(
    private val shelf: List<Saver<*>>,
    private val _contextData: Map<String, JsonElement> = emptyMap(),
    private val processConfig: ScribeProcessConfig = ScribeProcessConfig(),
    internal val margins: Margin? = null,
    onUncaughtException: ((Throwable) -> Unit)? = null,
) : AutoCloseable {
    private val scrollsById = mutableMapOf<String, Scroll>()
    private val scrollsMutex = Mutex()
    internal val queue = Channel<Record>(
        capacity = processConfig.bufferSize,
        onBufferOverflow = processConfig.overflowStrategy,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processorJob: Job

    val scrolls: List<Scroll>
        get() = runBlocking { scrollsMutex.withLock { scrollsById.values.toList() } }

    init {
        require(processConfig.bufferSize >= -2) { "BufferSize must be >= -2. Check Channel documentation" }
        require(shelf.isNotEmpty()) { "At least one shelf is required." }
        if (onUncaughtException != null) {
            installUncaughtExceptionHandler(onUncaughtException)
        }
        processorJob = processScope.launch {
            for (record in queue) {
                shelf.forEach { saver ->
                    try {
                        when (saver) {
                            is RecordSaver -> saver.write(record)
                            is ScrollSaver if record is SealedScroll -> saver.write(record)
                            is NoteSaver if record is Note -> saver.write(record)
                        }
                    } catch (e: Throwable) {
                        processConfig.onSinkError(saver, record, e)
                    }
                }
            }
        }
    }

    fun startScroll(id: String? = null): Scroll = runBlocking {
        scrollsMutex.withLock {
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
                context = this@Scribe,
                contextData = _contextData.toMap(),
            )
            margins?.header(scroll)
            scrollsById[resolvedId] = scroll
            scroll
        }
    }

    suspend fun captureScroll(
        id: String? = null,
        block: suspend Scroll.() -> Unit = {},
    ): SealedScroll {
        val scroll = startScroll(id = id)
        val throwable = try {
            scroll.block()
            null
        } catch (t: Throwable) {
            t
        }
        val sealed = scroll.seal(success = throwable == null, error = throwable)
        if (throwable != null) throw throwable
        return sealed
    }

    fun captureScrollBlocking(
        id: String? = null,
        block: Scroll.() -> Unit = {},
    ): SealedScroll {
        val scroll = startScroll(id = id)
        val throwable = try {
            scroll.block()
            null
        } catch (t: Throwable) {
            t
        }
        val sealed = scroll.sealBlocking(success = throwable == null, error = throwable)
        if (throwable != null) throw throwable
        return sealed
    }

    suspend fun note(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        timestamp: Long = nowEpochMs(),
    ) {
        queue.send(
            Note(
                tag = tag,
                message = message,
                level = level,
                timestamp = timestamp,
            ),
        )
    }

    override fun close() {
        queue.close()
        runBlocking { processorJob.join() }
        processScope.cancel()
    }

    fun noteBlocking(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        timestamp: Long = nowEpochMs(),
    ) {
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
