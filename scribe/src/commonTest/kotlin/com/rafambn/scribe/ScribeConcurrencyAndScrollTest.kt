package com.rafambn.scribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ScribeConcurrencyAndScrollTest {
    @Test
    fun scroll_seal_supports_high_throughput_concurrent_writes() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(
                shelf,
                bufferCapacity = Channel.UNLIMITED,
                bufferOverflow = BufferOverflow.SUSPEND,
            )

            coroutineScope {
                repeat(1_000) { index ->
                    launch(Dispatchers.Default) {
                        scribe.newScroll().apply {
                            this["msg"] = JsonPrimitive("msg-$index")
                        }.seal(scribe)
                    }
                }
            }

            shelf.awaitEvents(1_000)
            scribe.retire()

            assertEquals(1_000, shelf.events.size)
            assertEquals(
                1_000,
                shelf.events.map { it["msg"]?.jsonPrimitive?.content }.toSet().size,
            )
        }
    }

    @Test
    fun scroll_double_seal_emits_one_event_per_seal_call() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll(id = "scroll-id")

            scroll["state"] = JsonPrimitive("initial")
            scroll.seal(scribe)
            scroll.seal(scribe)
            shelf.awaitEvents(2)
            scribe.retire()

            assertEquals(2, shelf.events.size)
            shelf.events.forEach { event ->
                assertEquals(JsonPrimitive("initial"), event["state"])
                assertEquals("scroll-id", (event["scroll_id"] as? JsonPrimitive)?.content)
            }
        }
    }
}
