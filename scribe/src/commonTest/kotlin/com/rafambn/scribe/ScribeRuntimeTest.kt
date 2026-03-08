package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScribeRuntimeTest {
    @Test
    fun unrollScroll_creates_scroll_for_the_given_shelf() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            assertEquals(1, scribe.seekScrolls().size)
            assertSame(scroll, scribe.seekScrolls().single())

            scroll.writeSerializable("method", "card")
            scroll.seal(success = true)
            shelf.awaitEvents(1)
            scribe.retire()

            assertEquals(1, shelf.events.size)
            val event = shelf.events.single()
            assertEquals(scroll.id, event.scrollId)
            assertEquals(JsonPrimitive("card"), event.data["method"])
            assertTrue(event.success)
        }
    }

    @Test
    fun seal_is_idempotent_and_writes_once() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            scroll.writeSerializable("gateway", "stripe")
            assertFalse(scroll.isSealed)

            scroll.seal(success = false, error = IllegalStateException("fail"))
            scroll.seal(success = true)
            shelf.awaitEvents(1)
            scribe.retire()

            assertTrue(scroll.isSealed)
            assertEquals(1, shelf.events.size)

            val event = shelf.events.single()
            assertEquals(scroll.id, event.scrollId)
            assertFalse(event.success)
            assertEquals("fail", event.errorMessage)
            assertEquals(JsonPrimitive("stripe"), event.data["gateway"])
        }
    }

    @Test
    fun two_explicit_scrolls_match_requested_flow() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val paymentService = PaymentService()

            val scroll1 = scribe.unrollScroll()
            val scroll2 = scribe.unrollScroll()

            paymentService.pay("order1", scroll1)
            assertFailsWith<IllegalStateException> {
                paymentService.pay("order2", scroll2)
            }
            scroll1.seal(success = true)
            scroll2.seal(success = true)
            shelf.awaitEvents(2)
            scribe.retire()

            assertEquals(2, shelf.events.size)

            val successEvent = shelf.events.firstOrNull { it.scrollId == scroll1.id }
            val failureEvent = shelf.events.firstOrNull { it.scrollId == scroll2.id }

            assertNotNull(successEvent)
            assertNotNull(failureEvent)
            assertEquals(2, scribe.seekScrolls().size)

            assertTrue(successEvent.success)
            assertEquals(JsonPrimitive(scroll1.id), successEvent.data["scrollId"])
            assertEquals(JsonPrimitive("stripe"), successEvent.data["gateway"])

            assertFalse(failureEvent.success)
            assertEquals(JsonPrimitive(scroll2.id), failureEvent.data["scrollId"])
            assertEquals(JsonPrimitive("gateway_call"), failureEvent.data["error_stage"])
            assertEquals("order2 failed", failureEvent.errorMessage)
        }
    }

    @Test
    fun writeSerializable_stores_serializable_object_value() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            scroll.writeSerializable("meta", GatewayMeta(retries = 2))
            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(2))), event.data["meta"])
        }
    }

    @Test
    fun write_helpers_store_json_safe_values() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            scroll.writeString("message", "accepted")
            scroll.writeNumber("attempt", 3)
            scroll.writeBoolean("retry", false)
            scroll.writeSerializable("meta", GatewayMeta(retries = 2))
            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive("accepted"), event.data["message"])
            assertEquals(JsonPrimitive(3), event.data["attempt"])
            assertEquals(JsonPrimitive(false), event.data["retry"])
            assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(2))), event.data["meta"])
        }
    }

    @Test
    fun write_throws_for_custom_object_without_serializer() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            assertFailsWith<IllegalArgumentException> {
                scroll.writeSerializable("meta", NonSerializableMeta(retries = 2))
            }
        }
    }

    @Test
    fun writeNumber_throws_for_non_finite_values() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            assertFailsWith<IllegalArgumentException> {
                scroll.writeNumber("latency_ms", Double.NaN)
            }
        }
    }

    @Test
    fun generated_scroll_ids_are_unique_uuid_strings() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val ids = (1..500).map { scribe.unrollScroll().id }

            assertEquals(ids.size, ids.toSet().size)
            assertTrue(ids.all { UUID_REGEX.matches(it) })
        }
    }

    @Test
    fun unrollScroll_uses_custom_id() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll(id = "session-42")

            scroll.writeString("operation", "sync")
            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals("session-42", scroll.id)
            assertEquals("session-42", event.scrollId)
        }
    }

    @Test
    fun unrollScroll_throws_when_custom_id_already_exists() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            scribe.unrollScroll(id = "session-42")

            val thrown = assertFailsWith<IllegalArgumentException> {
                scribe.unrollScroll(id = "session-42")
            }

            assertEquals("A scroll with id 'session-42' already exists.", thrown.message)
        }
    }

    @Test
    fun unrollScroll_includes_context_data_in_event() {
        runSuspend {
            val shelf = RecordingShelf()
            val imprint = mapOf(
                "service" to JsonPrimitive("mobile-app"),
                "environment" to JsonPrimitive("production"),
            )
            val scribe = scribeWithScrollShelves(shelf, imprint = imprint)

            val scroll = scribe.unrollScroll()

            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive("mobile-app"), event.context["service"])
            assertEquals(JsonPrimitive("production"), event.context["environment"])
        }
    }

    @Test
    fun all_scrolls_share_same_context() {
        runSuspend {
            val shelf = RecordingShelf()
            val imprint = mapOf("region" to JsonPrimitive("us-east"))
            val scribe = scribeWithScrollShelves(shelf, imprint = imprint)

            val firstScroll = scribe.unrollScroll(id = "first")
            val secondScroll = scribe.unrollScroll(id = "second")

            firstScroll.seal()
            secondScroll.seal()
            shelf.awaitEvents(2)
            scribe.retire()

            val firstEvent = shelf.events.firstOrNull { it.scrollId == "first" }
            val secondEvent = shelf.events.firstOrNull { it.scrollId == "second" }

            assertNotNull(firstEvent)
            assertNotNull(secondEvent)
            assertEquals(JsonPrimitive("us-east"), firstEvent.context["region"])
            assertEquals(JsonPrimitive("us-east"), secondEvent.context["region"])
        }
    }

    @Test
    fun scroll_write_goes_to_data_field_separate_from_context() {
        runSuspend {
            val shelf = RecordingShelf()
            val imprint = mapOf("region" to JsonPrimitive("us-east"))
            val scribe = scribeWithScrollShelves(shelf, imprint = imprint)
            val scroll = scribe.unrollScroll()

            scroll.writeString("region", "ap-south")
            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive("us-east"), event.context["region"])
            assertEquals(JsonPrimitive("ap-south"), event.data["region"])
        }
    }

    @Test
    fun seekScrolls_returns_same_scroll_reference_with_shared_updates() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val originalScroll = scribe.unrollScroll(id = "shared")

            originalScroll.writeString("stage", "created")
            val sameScroll = scribe.seekScrolls().single()

            originalScroll.writeString("status", "updated")

            assertSame(originalScroll, sameScroll)
            assertEquals(JsonPrimitive("created"), sameScroll.read("stage"))
            assertEquals(JsonPrimitive("updated"), sameScroll.read("status"))
            scribe.retire()
        }
    }

    @Test
    fun events_are_dispatched_to_multiple_sinks() {
        runSuspend {
            val shelf1 = RecordingShelf()
            val shelf2 = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf1, shelf2)
            val scroll = scribe.unrollScroll(id = "scroll-a")

            scroll.seal()
            shelf1.awaitEvents(1)
            shelf2.awaitEvents(1)
            scribe.retire()

            assertEquals(1, shelf1.events.size)
            assertEquals(1, shelf2.events.size)
            assertEquals("scroll-a", shelf1.events.single().scrollId)
        }
    }

    @Test
    fun routes_can_select_notes_scrolls_or_both() {
        runSuspend {
            val scrollShelf = RecordingShelf()
            val noteSaver = RecordingNoteSaver()
            val allSaver = RecordingEntrySaver()
            val scribe = Scribe(
                shelves = listOf(
                    scrollShelf,
                    noteSaver,
                    allSaver,
                ),
            )

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 100L)
            scribe.unrollScroll(id = "scroll-1").seal()
            scrollShelf.awaitEvents(1)
            noteSaver.awaitEvents(1)
            allSaver.awaitEvents(2)
            scribe.retire()

            assertEquals(1, scrollShelf.events.size)
            assertEquals(1, noteSaver.events.size)
            assertEquals(2, allSaver.events.size)
            assertTrue(allSaver.events.any { it is Note })
            assertTrue(allSaver.events.any { it is SealedScroll })
        }
    }

    @Test
    fun seal_does_not_block_when_sink_is_slow() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate)
            val scribe = scribeWithScrollShelves(
                shelf,
                deliveryConfig = ScribeDeliveryConfig(bufferSize = 4, overflowStrategy = BufferOverflow.DROP_OLDEST),
            )
            val scroll = scribe.unrollScroll(id = "slow")

            scroll.seal()
            assertEquals(0, shelf.events.size)

            gate.complete(Unit)
            shelf.awaitEvents(1)
            scribe.retire()
            assertEquals(1, shelf.events.size)
        }
    }

    @Test
    fun processor_survives_idle_gap_between_sends() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)

            scribe.unrollScroll(id = "first").seal()
            delay(500)
            scribe.unrollScroll(id = "second").seal()
            shelf.awaitEvents(2)
            scribe.retire()

            assertEquals(2, shelf.events.size)
            assertTrue(shelf.events.any { it.scrollId == "first" })
            assertTrue(shelf.events.any { it.scrollId == "second" })
        }
    }

    @Test
    fun scribe_outlives_creator_function_scope_until_explicit_retire() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)

            val scribe = createScribeInHelperAndEmit(shelf)

            firstWriteStarted.await()
            assertEquals(0, shelf.events.size)

            gate.complete(Unit)
            shelf.awaitEvents(1)
            scribe.retire()

            assertEquals(1, shelf.events.size)
            assertEquals("scoped", shelf.events.single().scrollId)
        }
    }

    @Test
    fun retire_returns_immediately_without_waiting_for_drain() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(shelf)

            scribe.unrollScroll(id = "in-flight").seal()
            firstWriteStarted.await()

            // retire() is non-suspending and returns immediately even while the processor is blocked
            scribe.retire()
            assertEquals(0, shelf.events.size)

            // processor drains the remaining event once the gate opens
            gate.complete(Unit)
            shelf.awaitEvents(1)
            assertEquals("in-flight", shelf.events.single().scrollId)
        }
    }

    @Test
    fun planRetire_waits_for_pending_events_to_flush() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(shelf)

            scribe.unrollScroll(id = "flush-me").seal()
            firstWriteStarted.await()

            val retireScope = CoroutineScope(Dispatchers.Default)
            val retireJob = retireScope.launch { scribe.planRetire() }
            delay(50)
            assertFalse(retireJob.isCompleted)

            gate.complete(Unit)
            withTimeout(2_000) {
                retireJob.join()
            }
            retireScope.cancel()
            assertEquals(1, shelf.events.size)
            assertEquals("flush-me", shelf.events.single().scrollId)
        }
    }

    @Test
    fun planRetire_called_from_saver_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val saver = EntrySaver {
                scribe.planRetire()
                retired.complete(Unit)
            }
            scribe = Scribe(
                shelves = listOf(saver),
            )

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 1L)
            withTimeout(2_000) {
                retired.await()
            }
        }
    }

    @Test
    fun planRetire_called_from_saver_child_coroutine_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val saver = EntrySaver {
                coroutineScope {
                    launch {
                        scribe.planRetire()
                        retired.complete(Unit)
                    }
                }
            }
            scribe = Scribe(
                shelves = listOf(saver),
            )

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 2L)
            withTimeout(2_000) {
                retired.await()
            }
        }
    }

    @Test
    fun drop_latest_overflow_can_drop_events_under_pressure() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(
                shelf,
                deliveryConfig = ScribeDeliveryConfig(bufferSize = 1, overflowStrategy = BufferOverflow.DROP_LATEST),
            )

            scribe.unrollScroll(id = "one").seal()
            firstWriteStarted.await()
            scribe.unrollScroll(id = "two").seal()
            scribe.unrollScroll(id = "three").seal()

            gate.complete(Unit)
            shelf.awaitEvents(2)
            scribe.retire()

            assertTrue(shelf.events.any { it.scrollId == "one" })
            assertTrue(shelf.events.any { it.scrollId == "two" })
            assertFalse(shelf.events.any { it.scrollId == "three" })
        }
    }

    @Test
    fun onSaverError_is_called_and_other_savers_continue() {
        runSuspend {
            val events = mutableListOf<Entry>()
            val errors = mutableListOf<Throwable>()
            val failingSaver = EntrySaver { throw IllegalStateException("boom") }
            val recordingSaver = RecordingEntrySaver()
            val scribe = Scribe(
                shelves = listOf(failingSaver, recordingSaver),
                deliveryConfig = ScribeDeliveryConfig(
                    onSaverError = { _, entry, error ->
                        events += entry
                        errors += error
                    },
                ),
            )

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 42L)
            recordingSaver.awaitEvents(1)
            scribe.retire()

            assertEquals(1, recordingSaver.events.size)
            assertEquals(1, events.size)
            assertEquals(1, errors.size)
            assertTrue(events.single() is Note)
            assertEquals("boom", errors.single().message)
        }
    }

    @Test
    fun custom_margin_can_add_timestamps() {
        runSuspend {
            val shelf = RecordingShelf()
            val timestampMargin = object : Margin {
                override fun header(scroll: Scroll) {
                    scroll.writeNumber("startedAtEpochMs", 1000L)
                }
                override fun footer(scroll: Scroll) {
                    scroll.writeNumber("sealedAtEpochMs", 2000L)
                }
            }
            val scribe = scribeWithScrollShelves(shelf, margins = timestampMargin)
            val scroll = scribe.unrollScroll()

            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive(1000L), event.data["startedAtEpochMs"])
            assertEquals(JsonPrimitive(2000L), event.data["sealedAtEpochMs"])
        }
    }

    @Test
    fun no_margin_means_no_timestamps() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf, margins = null)
            val scroll = scribe.unrollScroll()

            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertFalse(event.data.containsKey("startedAtEpochMs"))
            assertFalse(event.data.containsKey("sealedAtEpochMs"))
        }
    }

    @Test
    fun custom_margin_can_add_elapsed_time() {
        runSuspend {
            val shelf = RecordingShelf()
            val elapsedMargin = object : Margin {
                override fun header(scroll: Scroll) {
                    scroll.writeNumber("_startTime", 1000L)
                }
                override fun footer(scroll: Scroll) {
                    val startExists = scroll.read("_startTime") != null
                    if (startExists) {
                        scroll.erase("_startTime")
                        scroll.writeNumber("elapsedMs", 500L)
                    }
                }
            }
            val scribe = scribeWithScrollShelves(shelf, margins = elapsedMargin)
            val scroll = scribe.unrollScroll()

            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive(500L), event.data["elapsedMs"])
            assertFalse(event.data.containsKey("_startTime"))
        }
    }

    @Test
    fun margin_header_runs_before_footer() {
        runSuspend {
            val shelf = RecordingShelf()
            val calls = mutableListOf<String>()
            val margin = object : Margin {
                override fun header(scroll: Scroll) { calls.add("header") }
                override fun footer(scroll: Scroll) { calls.add("footer") }
            }
            val scribe = scribeWithScrollShelves(shelf, margins = margin)
            val scroll = scribe.unrollScroll()

            scroll.seal()
            scribe.retire()

            assertEquals(listOf("header", "footer"), calls)
        }
    }

    @Test
    fun scroll_read_and_erase_work() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            scroll.writeString("key", "value")
            assertEquals(JsonPrimitive("value"), scroll.read("key"))
            val removed = scroll.erase("key")
            assertEquals(JsonPrimitive("value"), removed)
            assertNull(scroll.read("key"))
            scribe.retire()
        }
    }

    @Test
    fun read_and_erase_on_sealed_scroll_return_null() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.unrollScroll()

            scroll.writeString("key", "value")
            scroll.seal()
            assertNull(scroll.erase("key"))
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive("value"), event.data["key"])
        }
    }

    private class PaymentService {
        suspend fun pay(orderId: String, scroll: Scroll) {
            try {
                scroll.writeSerializable("scrollId", scroll.id)
                if (orderId == "order2") {
                    throw IllegalStateException("order2 failed")
                }
                scroll.writeSerializable("gateway", "stripe")
            } catch (t: Throwable) {
                scroll.writeSerializable("error_stage", "gateway_call")
                scroll.seal(success = false, error = t)
                throw t
            }
        }
    }

    @Serializable
    private data class GatewayMeta(val retries: Int)

    private data class NonSerializableMeta(val retries: Int)

    private class RecordingShelf : ScrollSaver {
        val events = mutableListOf<SealedScroll>()
        private val writes = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun write(event: SealedScroll) {
            events += event
            writes.trySend(Unit)
        }

        suspend fun awaitEvents(count: Int) {
            repeat(count) {
                writes.receive()
            }
        }
    }

    private class BlockingShelf(
        private val gate: CompletableDeferred<Unit>,
        private val firstWriteStarted: CompletableDeferred<Unit>? = null,
    ) : ScrollSaver {
        val events = mutableListOf<SealedScroll>()
        private val writes = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun write(event: SealedScroll) {
            firstWriteStarted?.complete(Unit)
            gate.await()
            events += event
            writes.trySend(Unit)
        }

        suspend fun awaitEvents(count: Int) {
            repeat(count) {
                writes.receive()
            }
        }
    }

    private class RecordingNoteSaver : NoteSaver {
        val events = mutableListOf<Note>()
        private val writes = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun write(event: Note) {
            events += event
            writes.trySend(Unit)
        }

        suspend fun awaitEvents(count: Int) {
            repeat(count) {
                writes.receive()
            }
        }
    }

    private class RecordingEntrySaver : EntrySaver {
        val events = mutableListOf<Entry>()
        private val writes = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun write(event: Entry) {
            events += event
            writes.trySend(Unit)
        }

        suspend fun awaitEvents(count: Int) {
            repeat(count) {
                writes.receive()
            }
        }
    }
}

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun scribeWithScrollShelves(
    vararg shelves: ScrollSaver,
    imprint: Map<String, JsonElement> = emptyMap(),
    deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
    margins: Margin? = null,
): Scribe = Scribe(
    shelves = shelves.toList(),
    imprint = imprint,
    deliveryConfig = deliveryConfig,
    margins = margins,
)

private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

private suspend fun createScribeInHelperAndEmit(shelf: ScrollSaver): Scribe {
    val scribe = scribeWithScrollShelves(shelf)
    scribe.unrollScroll(id = "scoped").seal()
    return scribe
}
