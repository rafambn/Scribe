package com.rafambn.scribe

import com.rafambn.scribe.internal.newScrollId
import com.rafambn.scribe.internal.nowEpochMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement

class Scribe(
    val shelves: List<Shelf>,
    val contextData: MutableMap<String, JsonElement> = mutableMapOf(),
    val processConfig: ScribeProcessConfig = ScribeProcessConfig(),
) {
    private val scrollsById = mutableMapOf<String, Scroll>()
    private val queue = Channel<DispatchMessage>(
        capacity = processConfig.bufferSize,
        onBufferOverflow = processConfig.overflowStrategy,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processorJob = processScope.launch {
        while (isActive) {
            select {
                queue.onReceive {
                    when (it) {
                        is DispatchMessage.ScrollMessage -> {
                            shelves.forEach { shelf ->
                                try {
                                    shelf.write(it.event)
                                } catch (_: Throwable) {
                                    // Keep processing even if one sink fails.
                                }
                            }
                        }

                        is DispatchMessage.NoteMessage -> {
                            shelves.forEach { shelf ->
                                try {
                                    shelf.writeNote(it.event)
                                } catch (_: Throwable) {
                                    // Keep processing even if one sink fails.
                                }
                            }
                        }

                        is DispatchMessage.FlushMessage -> it.done.complete(Unit)
                    }
                }
            }
        }
    }

    val scrolls: List<Scroll>
        get() = scrollsById.values.toList()

    init {
        require(processConfig.bufferSize >= -2) { "BufferSize must be >= -2. Check Channel documentation" }
        require(shelves.isNotEmpty()) { "At least one shelf is required." }
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
            initialData = contextData.toMap(),
            startedAtEpochMs = nowEpochMs(),
        )
        scrollsById[resolvedId] = scroll
        return scroll
    }

    fun debug(message: String, scrollId: String? = null) {
        note(ScribeNoteLevel.DEBUG, message, scrollId)
    }

    fun info(message: String, scrollId: String? = null) {
        note(ScribeNoteLevel.INFO, message, scrollId)
    }

    fun warn(message: String, scrollId: String? = null) {
        note(ScribeNoteLevel.WARN, message, scrollId)
    }

    fun error(message: String, scrollId: String? = null) {
        note(ScribeNoteLevel.ERROR, message, scrollId)
    }

    fun note(level: ScribeNoteLevel, message: String, scrollId: String? = null) {
        DispatchMessage.NoteMessage(
            ScribeNoteEvent(
                level = level,
                message = message,
                scrollId = scrollId,
                createdAtEpochMs = nowEpochMs(),
            ),
        )
    }

    suspend inline fun <T> captureScroll(
        id: String? = null,
        block: suspend Scroll.() -> T,
    ): T {
        val scroll = startScroll(id = id)
        return scroll.use(block)
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.send(DispatchMessage.FlushMessage(done))
        done.await()
    }

    suspend fun close(flush: Boolean = true) {
        if (flush) {
            this.flush()
        }
        queue.close()
        processorJob.join()
    }

    internal suspend fun write(event: SealedScrollEvent) {
        queue.send(DispatchMessage.ScrollMessage(event))
    }
}

private sealed interface DispatchMessage {
    data class ScrollMessage(val event: SealedScrollEvent) : DispatchMessage
    data class NoteMessage(val event: ScribeNoteEvent) : DispatchMessage
    data class FlushMessage(val done: CompletableDeferred<Unit>) : DispatchMessage
}
