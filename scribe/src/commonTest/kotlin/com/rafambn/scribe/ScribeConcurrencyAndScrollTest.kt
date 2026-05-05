package com.rafambn.scribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ScribeConcurrencyAndScrollTest {
    @Test
    fun note_supports_high_throughput_concurrent_writes() {
        runSuspend {
            val saver = RecordingNoteSaver()
            val scribe = scribeWithSavers(shelves = listOf(saver))

            coroutineScope {
                repeat(1_000) { index ->
                    launch(Dispatchers.Default) {
                        scribe.note(
                            tag = "stress",
                            message = "msg-$index",
                            level = Urgency.INFO,
                            timestamp = index.toLong(),
                        )
                    }
                }
            }

            saver.awaitEvents(1_000)
            scribe.retire()

            assertEquals(1_000, saver.events.size)
            assertEquals(1_000, saver.events.map { it.message }.toSet().size)
        }
    }

    @Test
    fun scroll_double_seal_emits_one_event_per_seal_call() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll(id = "scroll-id")

            scroll["state"] = JsonPrimitive("initial")
            val first = scroll.seal(success = false)
            val second = scroll.seal(success = true)
            shelf.awaitEvents(2)
            scribe.retire()

            assertEquals(false, first.success)
            assertEquals(JsonPrimitive("initial"), first.data["state"])

            assertEquals(true, second.success)
            assertEquals(JsonPrimitive("initial"), second.data["state"])
            assertEquals(2, shelf.events.size)
        }
    }
}
