package com.rafambn.scribe

import com.rafambn.scribe.internal.newScrollId
import com.rafambn.scribe.internal.nowEpochMs

class Scribe(
    private val shelf: Shelf,
) {
    private val scrollsById = mutableMapOf<String, Scroll>()

    val scrolls: List<Scroll>
        get() = scrollsById.values.toList()

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
            startedAtEpochMs = nowEpochMs(),
        )
        scrollsById[resolvedId] = scroll
        return scroll
    }

    suspend inline fun <T> captureScroll(
        id: String? = null,
        block: suspend Scroll.() -> T,
    ): T {
        val scroll = startScroll(id = id)
        return scroll.use(block)
    }

    internal suspend fun write(event: SealedScrollEvent) {
        shelf.write(event)
    }
}
