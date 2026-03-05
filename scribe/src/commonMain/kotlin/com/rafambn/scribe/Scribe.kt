package com.rafambn.scribe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement

class Scribe(
    val shelf: List<Saver<*>>,
    val contextData: MutableMap<String, JsonElement> = mutableMapOf(),
    val processConfig: ScribeProcessConfig = ScribeProcessConfig(),
    val margins: Margin? = null,
) {
    private val scrollsById = mutableMapOf<String, Scroll>()
    internal val queue = Channel<Record>(
        capacity = processConfig.bufferSize,
        onBufferOverflow = processConfig.overflowStrategy,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processorJob = processScope.launch {
        while (isActive) {
            select {
                queue.onReceive { record ->
                    shelf.forEach { saver ->
                        try {
                            when (saver) {
                                is RecordSaver -> saver.write(record)
                                is ScrollSaver if record is SealedScroll -> saver.write(record)
                                is NoteSaver if record is Note -> saver.write(record)
                            }
                        } catch (_: Throwable) {
                            // Keep processing even if one sink fails.
                        }
                    }
                }
            }
        }
    }

    val scrolls: List<Scroll>
        get() = scrollsById.values.toList()

    init {
        require(processConfig.bufferSize >= -2) { "BufferSize must be >= -2. Check Channel documentation" }
        require(shelf.isNotEmpty()) { "At least one shelf is required." }
    }

    fun startScroll(id: String? = null): Scroll {
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
            context = this,
            contextData = contextData.toMap(),
        )
        margins?.header(scroll)
        scrollsById[resolvedId] = scroll
        return scroll
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

    suspend fun close() {
        queue.close()
        processorJob.join()
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