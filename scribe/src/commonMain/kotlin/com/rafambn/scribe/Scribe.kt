package com.rafambn.scribe

import com.rafambn.scribe.internal.newScrollId
import com.rafambn.scribe.internal.nowEpochMs

class Scribe(
    private val shelf: Shelf,
) {
    private val scrollsById = linkedMapOf<Int, Scroll>()

    val scrolls: List<Scroll>
        get() = scrollsById.values.toList()

    fun startScroll(): Scroll {
        var id = newScrollId()
        while (scrollsById.containsKey(id)) {
            id = newScrollId()
        }

        val scroll = Scroll(
            id = id,
            context = this,
            startedAtEpochMs = nowEpochMs(),
        )
        scrollsById[id] = scroll
        return scroll
    }

    suspend inline fun <T> captureScroll(block: suspend Scroll.() -> T): T {
        val scroll = startScroll()
        return scroll.use(block)
    }

    internal suspend fun write(event: SealedScrollEvent) {
        shelf.write(event)
    }
}
