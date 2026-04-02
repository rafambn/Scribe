package com.rafambn.scribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun scroll_double_seal_is_idempotent_and_emits_only_once() {
        runSuspend {
            var onSealCalls = 0
            var onSealedCalls = 0
            var emitted = 0

            val scroll = Scroll(
                id = "scroll-id",
                onSeal = { onSealCalls++ },
                onSealed = { onSealedCalls++ },
                emitSealedScroll = { emitted++ },
            )

            scroll.writeString("state", "initial")
            val first = scroll.seal(success = false, error = IllegalStateException("first"))
            val second = scroll.seal(success = true, error = null)

            assertTrue(scroll.isSealed)
            assertEquals(1, onSealCalls)
            assertEquals(1, onSealedCalls)
            assertEquals(1, emitted)
            assertEquals(false, first.success)
            assertEquals("first", first.errorMessage)
            assertEquals(JsonPrimitive("initial"), first.data["state"])

            // Repeated seal does not emit again, but returns a derived result from the second invocation arguments.
            assertEquals(true, second.success)
            assertEquals(null, second.errorMessage)
            assertEquals(JsonPrimitive("initial"), second.data["state"])
        }
    }
}
