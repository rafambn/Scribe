package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScribeRuntimeTest {
    @Test
    fun startScroll_creates_scroll_for_the_given_shelf() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        assertSame(scribe, scroll.context)
        assertEquals(1, scribe.scrolls.size)
        assertSame(scroll, scribe.scrolls.single())

        runSuspend {
            scroll.put("method", "card")
            scroll.seal(success = true)
            scribe.flush()
        }

        assertEquals(1, shelf.events.size)
        val event = shelf.events.single()
        assertEquals(scroll.id, event.scrollId)
        assertEquals(JsonPrimitive("card"), event.data["method"])
        assertTrue(event.success)
    }

    @Test
    fun seal_is_idempotent_and_writes_once() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        scroll.put("gateway", "stripe")
        assertFalse(scroll.isSealed)

        runSuspend {
            scroll.seal(success = false, error = IllegalStateException("fail"))
            scroll.seal(success = true)
            scribe.flush()
        }

        assertTrue(scroll.isSealed)
        assertEquals(1, shelf.events.size)

        val event = shelf.events.single()
        assertEquals(scroll.id, event.scrollId)
        assertFalse(event.success)
        assertEquals("fail", event.errorMessage)
        assertEquals(JsonPrimitive("stripe"), event.data["gateway"])
    }

    @Test
    fun two_explicit_scrolls_match_requested_flow() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val paymentService = PaymentService()

        val scroll1 = scribe.startScroll()
        val scroll2 = scribe.startScroll()

        runSuspend {
            paymentService.pay("order1", scroll1)
            assertFailsWith<IllegalStateException> {
                paymentService.pay("order2", scroll2)
            }
            scroll1.seal(success = true)
            scroll2.seal(success = true)
            scribe.flush()
        }

        assertEquals(2, shelf.events.size)

        val successEvent = shelf.events.firstOrNull { it.scrollId == scroll1.id }
        val failureEvent = shelf.events.firstOrNull { it.scrollId == scroll2.id }

        assertNotNull(successEvent)
        assertNotNull(failureEvent)
        assertEquals(2, scribe.scrolls.size)

        assertTrue(successEvent.success)
        assertEquals(JsonPrimitive(scroll1.id), successEvent.data["scrollId"])
        assertEquals(JsonPrimitive("stripe"), successEvent.data["gateway"])

        assertFalse(failureEvent.success)
        assertEquals(JsonPrimitive(scroll2.id), failureEvent.data["scrollId"])
        assertEquals(JsonPrimitive("gateway_call"), failureEvent.data["error_stage"])
        assertEquals("order2 failed", failureEvent.errorMessage)
    }

    @Test
    fun putSerializable_stores_serializable_object_value() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        runSuspend {
            scroll.put("meta", GatewayMeta(retries = 2))
            scroll.seal()
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(2))), event.data["meta"])
    }

    @Test
    fun put_helpers_store_json_safe_values() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        runSuspend {
            scroll.putString("message", "accepted")
            scroll.putNumber("attempt", 3)
            scroll.putBoolean("retry", false)
            scroll.putSerializable("meta", GatewayMeta(retries = 2))
            scroll.putObject("details", GatewayMeta(retries = 5))
            scroll.seal()
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals(JsonPrimitive("accepted"), event.data["message"])
        assertEquals(JsonPrimitive(3), event.data["attempt"])
        assertEquals(JsonPrimitive(false), event.data["retry"])
        assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(2))), event.data["meta"])
        assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(5))), event.data["details"])
    }

    @Test
    fun put_throws_for_custom_object_without_serializer() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        assertFailsWith<IllegalArgumentException> {
            scroll.put("meta", NonSerializableMeta(retries = 2))
        }
    }

    @Test
    fun putObject_throws_for_non_object_json_values() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        assertFailsWith<IllegalArgumentException> {
            scroll.putObject("attempt", 2)
        }
    }

    @Test
    fun putNumber_throws_for_non_finite_values() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        assertFailsWith<IllegalArgumentException> {
            scroll.putNumber("latency_ms", Double.NaN)
        }
    }

    @Test
    fun generated_scroll_ids_are_unique_uuid_strings() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val ids = (1..500).map { scribe.startScroll().id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { UUID_REGEX.matches(it) })
    }

    @Test
    fun startScroll_uses_custom_id() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll(id = "session-42")

        runSuspend {
            scroll.putString("operation", "sync")
            scroll.seal()
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals("session-42", scroll.id)
        assertEquals("session-42", event.scrollId)
    }

    @Test
    fun startScroll_throws_when_custom_id_already_exists() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        scribe.startScroll(id = "session-42")

        val thrown = assertFailsWith<IllegalArgumentException> {
            scribe.startScroll(id = "session-42")
        }

        assertEquals("A scroll with id 'session-42' already exists.", thrown.message)
    }

    @Test
    fun startScroll_includes_context_data_in_event() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        scribe.contextData["service"] = JsonPrimitive("mobile-app")
        scribe.contextData["environment"] = JsonPrimitive("production")

        val scroll = scribe.startScroll()

        runSuspend {
            scroll.seal()
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals(JsonPrimitive("mobile-app"), event.data["service"])
        assertEquals(JsonPrimitive("production"), event.data["environment"])
    }

    @Test
    fun startScroll_uses_context_snapshot_at_creation_time() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        scribe.contextData["region"] = JsonPrimitive("us-east")

        val firstScroll = scribe.startScroll(id = "first")
        scribe.contextData["region"] = JsonPrimitive("eu-west")
        val secondScroll = scribe.startScroll(id = "second")

        runSuspend {
            firstScroll.seal()
            secondScroll.seal()
            scribe.flush()
        }

        val firstEvent = shelf.events.firstOrNull { it.scrollId == "first" }
        val secondEvent = shelf.events.firstOrNull { it.scrollId == "second" }

        assertNotNull(firstEvent)
        assertNotNull(secondEvent)
        assertEquals(JsonPrimitive("us-east"), firstEvent.data["region"])
        assertEquals(JsonPrimitive("eu-west"), secondEvent.data["region"])
    }

    @Test
    fun scroll_put_overrides_context_data_for_the_current_scroll() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        scribe.contextData["region"] = JsonPrimitive("us-east")
        val scroll = scribe.startScroll()

        runSuspend {
            scroll.putString("region", "ap-south")
            scroll.seal()
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals(JsonPrimitive("ap-south"), event.data["region"])
    }

    @Test
    fun captureScroll_seals_successfully_and_returns_result() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))

        val result = runSuspend {
            val value = scribe.captureScroll {
                putString("operation", "checkout")
                "ok"
            }
            scribe.flush()
            value
        }

        assertEquals("ok", result)
        assertEquals(1, shelf.events.size)
        assertEquals(1, scribe.scrolls.size)
        assertTrue(scribe.scrolls.single().isSealed)

        val event = shelf.events.single()
        assertTrue(event.success)
        assertEquals(null, event.errorMessage)
        assertEquals(JsonPrimitive("checkout"), event.data["operation"])
    }

    @Test
    fun captureScroll_seals_failure_and_rethrows() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))

        val thrown = assertFailsWith<IllegalStateException> {
            runSuspend {
                scribe.captureScroll {
                    putString("operation", "checkout")
                    throw IllegalStateException("gateway failed")
                }
            }
        }
        runSuspend { scribe.flush() }

        assertEquals("gateway failed", thrown.message)
        assertEquals(1, shelf.events.size)

        val event = shelf.events.single()
        assertFalse(event.success)
        assertEquals("gateway failed", event.errorMessage)
        assertEquals(JsonPrimitive("checkout"), event.data["operation"])
    }

    @Test
    fun captureScroll_accepts_custom_id() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))

        runSuspend {
            scribe.captureScroll(id = "flow-mobile-1") {
                putString("operation", "checkout")
            }
            scribe.flush()
        }

        val event = shelf.events.single()
        assertEquals("flow-mobile-1", event.scrollId)
    }

    @Test
    fun scribe_info_emits_info_note_with_scroll_id() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))
        val scroll = scribe.startScroll()

        runSuspend {
            scribe.info("charging card", scrollId = scroll.id)
            scroll.seal()
            scribe.flush()
        }

        assertEquals(1, shelf.notes.size)
        val note = shelf.notes.single()
        assertEquals(ScribeNoteLevel.INFO, note.level)
        assertEquals("charging card", note.message)
        assertEquals(scroll.id, note.scrollId)
    }

    @Test
    fun scribe_emits_all_note_levels() {
        val shelf = RecordingShelf()
        val scribe = Scribe(listOf(shelf))

        scribe.debug("d")
        scribe.info("i")
        scribe.warn("w")
        scribe.error("e")
        runSuspend { scribe.flush() }

        assertEquals(
            listOf(ScribeNoteLevel.DEBUG, ScribeNoteLevel.INFO, ScribeNoteLevel.WARN, ScribeNoteLevel.ERROR),
            shelf.notes.map { it.level },
        )
    }

    @Test
    fun events_and_notes_are_dispatched_to_multiple_sinks() {
        val shelf1 = RecordingShelf()
        val shelf2 = RecordingShelf()
        val scribe = Scribe(listOf(shelf1, shelf2))
        val scroll = scribe.startScroll(id = "scroll-a")

        scribe.info("starting", scrollId = "scroll-a")
        runSuspend {
            scroll.seal()
            scribe.flush()
        }

        assertEquals(1, shelf1.events.size)
        assertEquals(1, shelf2.events.size)
        assertEquals(1, shelf1.notes.size)
        assertEquals(1, shelf2.notes.size)
        assertEquals("scroll-a", shelf1.events.single().scrollId)
        assertEquals("starting", shelf2.notes.single().message)
    }

    @Test
    fun seal_does_not_block_when_sink_is_slow() {
        val gate = CompletableDeferred<Unit>()
        val shelf = BlockingShelf(gate)
        val scribe = Scribe(
            shelves = listOf(shelf),
            processConfig = ScribeProcessConfig(bufferSize = 4, overflowStrategy = BufferOverflow.DROP_OLDEST),
        )
        val scroll = scribe.startScroll(id = "slow")

        runSuspend {
            scroll.seal()
        }
        assertEquals(0, shelf.events.size)

        gate.complete(Unit)
        runSuspend { scribe.flush() }
        assertEquals(1, shelf.events.size)
    }

    @Test
    fun drop_latest_overflow_can_drop_events_under_pressure() {
        val gate = CompletableDeferred<Unit>()
        val shelf = BlockingShelf(gate)
        val scribe = Scribe(
            shelves = listOf(shelf),
            processConfig = ScribeProcessConfig(bufferSize = 1, overflowStrategy = BufferOverflow.DROP_LATEST),
        )

        runSuspend {
            scribe.startScroll(id = "one").seal()
            scribe.startScroll(id = "two").seal()
            scribe.startScroll(id = "three").seal()
        }

        gate.complete(Unit)
        runSuspend { scribe.flush() }

        assertFalse(shelf.events.any { it.scrollId == "three" })
    }

    private class PaymentService {
        suspend fun pay(orderId: String, scroll: Scroll) {
            try {
                scroll.put("scrollId", scroll.id)
                if (orderId == "order2") {
                    throw IllegalStateException("order2 failed")
                }
                scroll.put("gateway", "stripe")
            } catch (t: Throwable) {
                scroll.put("error_stage", "gateway_call")
                scroll.seal(success = false, error = t)
                throw t
            }
        }
    }

    @Serializable
    private data class GatewayMeta(val retries: Int)

    private data class NonSerializableMeta(val retries: Int)

    private class RecordingShelf : Shelf() {
        val events = mutableListOf<SealedScrollEvent>()
        val notes = mutableListOf<ScribeNoteEvent>()

        override suspend fun write(event: SealedScrollEvent) {
            events += event
        }

        override suspend fun writeNote(event: ScribeNoteEvent) {
            notes += event
        }
    }

    private class BlockingShelf(
        private val gate: CompletableDeferred<Unit>,
    ) : Shelf() {
        val events = mutableListOf<SealedScrollEvent>()

        override suspend fun write(event: SealedScrollEvent) {
            gate.await()
            events += event
        }
    }
}

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }
