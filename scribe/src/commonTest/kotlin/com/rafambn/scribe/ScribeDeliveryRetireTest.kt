package com.rafambn.scribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ScribeDeliveryRetireTest {
    @Test
    fun separate_scribe_instances_deliver_and_retire_independently() {
        runSuspend {
            val firstShelf = RecordingShelf()
            val secondShelf = RecordingShelf()
            val first = scribeWithScrollShelves(firstShelf)
            val second = scribeWithScrollShelves(secondShelf)

            first.newScroll(id = "first-runtime").seal(first)
            second.newScroll(id = "second-runtime").seal(second)
            firstShelf.awaitEvents(1)
            secondShelf.awaitEvents(1)

            first.retire()
            second.newScroll(id = "second-still-active").seal(second)
            secondShelf.awaitEvents(1)
            second.retire()

            assertEquals(1, firstShelf.events.size)
            assertEquals(2, secondShelf.events.size)
            assertEquals(
                "second-still-active",
                secondShelf.events.last()["scroll_id"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun events_are_dispatched_to_multiple_sinks() {
        runSuspend {
            val shelf1 = RecordingShelf()
            val shelf2 = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf1, shelf2)

            scribe.newScroll(id = "scroll-a").seal(scribe)
            shelf1.awaitEvents(1)
            shelf2.awaitEvents(1)
            scribe.retire()

            assertEquals(1, shelf1.events.size)
            assertEquals(1, shelf2.events.size)
        }
    }

    @Test
    fun scroll_events_reach_scroll_and_entry_savers() {
        runSuspend {
            val scrollShelf = RecordingShelf()
            val allSaver = RecordingEntrySaver()
            val scribe = scribeWithSavers(
                shelves = listOf(scrollShelf, allSaver),
            )

            scribe.newScroll(id = "scroll-1").seal(scribe)
            scrollShelf.awaitEvents(1)
            allSaver.awaitEvents(1)
            scribe.retire()

            assertEquals(1, scrollShelf.events.size)
            assertEquals(
                "scroll-1",
                scrollShelf.events.single()["scroll_id"]?.jsonPrimitive?.content,
            )
            val entry = allSaver.events.single() as ScrollEntry
            assertEquals("scroll-1", entry["scroll_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun custom_entries_are_dispatched_only_to_matching_and_wildcard_savers() {
        runSuspend {
            val customEvents = mutableListOf<CustomEntry>()
            val scrollEvents = mutableListOf<ScrollEntry>()
            val allEvents = mutableListOf<Entry>()
            val customWritten = CompletableDeferred<Unit>()
            val allWritten = CompletableDeferred<Unit>()
            val scribe = scribeWithSavers(
                shelves = listOf(
                    Saver<CustomEntry> {
                        customEvents += it
                        customWritten.complete(Unit)
                    },
                    Saver<ScrollEntry> { scrollEvents += it },
                    EntrySaver {
                        allEvents += it
                        allWritten.complete(Unit)
                    },
                ),
            )

            scribe.enqueue(CustomEntry("custom"))
            customWritten.await()
            allWritten.await()
            scribe.retire()

            assertEquals(listOf(CustomEntry("custom")), customEvents)
            assertTrue(scrollEvents.isEmpty())
            assertEquals(listOf<Entry>(CustomEntry("custom")), allEvents)
        }
    }

    @Test
    fun seal_does_not_block_when_sink_is_slow() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate)
            val scribe = scribeWithScrollShelves(
                shelf,
                channel = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST),
            )

            scribe.newScroll(id = "slow").seal(scribe)
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

            scribe.newScroll(id = "first").seal(scribe)
            delay(500.milliseconds)
            scribe.newScroll(id = "second").seal(scribe)
            shelf.awaitEvents(2)
            scribe.retire()

            assertEquals(2, shelf.events.size)
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

            assertEquals("scoped", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun retire_waits_for_inflight_delivery() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(shelf)

            scribe.newScroll(id = "in-flight").seal(scribe)
            firstWriteStarted.await()
            val retireScope = CoroutineScope(Dispatchers.Default)
            val retireJob = retireScope.launch { scribe.retire() }
            delay(50.milliseconds)
            assertFalse(retireJob.isCompleted)
            gate.complete(Unit)
            withTimeout(2_000.milliseconds) { retireJob.join() }
            retireScope.cancel()
            assertEquals("in-flight", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun seal_throws_after_retire() {
        runSuspend {
            val scribe = scribeWithScrollShelves(RecordingShelf())

            scribe.retire()
            assertFailsWith<IllegalStateException> {
                scribe.newScroll(id = "after").seal(scribe)
            }
        }
    }

    @Test
    fun retire_waits_for_pending_events_to_flush() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(shelf)

            scribe.newScroll(id = "flush-me").seal(scribe)
            firstWriteStarted.await()

            val retireScope = CoroutineScope(Dispatchers.Default)
            val retireJob = retireScope.launch { scribe.retire() }
            delay(50.milliseconds)
            assertFalse(retireJob.isCompleted)

            gate.complete(Unit)
            withTimeout(2_000.milliseconds) { retireJob.join() }
            retireScope.cancel()

            assertEquals("flush-me", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun retire_called_from_saver_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val saver = EntrySaver {
                scribe.retire()
                retired.complete(Unit)
            }
            scribe = scribeWithSavers(shelves = listOf(saver))

            scribe.newScroll(id = "retire-1").seal(scribe)
            withTimeout(2_000.milliseconds) { retired.await() }
        }
    }

    @Test
    fun retire_called_from_saver_child_coroutine_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val saver = EntrySaver {
                coroutineScope {
                    launch {
                        scribe.retire()
                        retired.complete(Unit)
                    }
                }
            }
            scribe = scribeWithSavers(shelves = listOf(saver))

            scribe.newScroll(id = "retire-2").seal(scribe)
            withTimeout(2_000.milliseconds) { retired.await() }
        }
    }

    @Test
    fun onSaverError_is_called_and_other_savers_continue() {
        runSuspend {
            val events = mutableListOf<Entry>()
            val errors = mutableListOf<Throwable>()
            val failingSaver = EntrySaver { throw IllegalStateException("boom") }
            val recordingSaver = RecordingEntrySaver()
            val scribe = scribeWithSavers(
                shelves = listOf(failingSaver, recordingSaver),
                onSaver = { _, entry, error ->
                    events += entry
                    errors += error
                },
            )

            scribe.newScroll(id = "error-1").seal(scribe)
            recordingSaver.awaitEvents(1)
            scribe.retire()

            assertEquals(1, recordingSaver.events.size)
            assertEquals(1, events.size)
            assertEquals(1, errors.size)
            val failedEntry = events.single() as ScrollEntry
            assertEquals("error-1", failedEntry["scroll_id"]?.jsonPrimitive?.content)
            assertEquals("boom", errors.single().message)
        }
    }

    @Test
    fun onSaverError_callback_failure_does_not_stop_delivery() {
        runSuspend {
            val failingSaver = EntrySaver { throw IllegalStateException("boom") }
            val recordingSaver = RecordingEntrySaver()
            val scribe = scribeWithSavers(
                shelves = listOf(failingSaver, recordingSaver),
                onSaver = { _, _, _ ->
                    throw IllegalStateException("callback-failed")
                },
            )

            scribe.newScroll(id = "first").seal(scribe)
            scribe.newScroll(id = "second").seal(scribe)
            recordingSaver.awaitEvents(2)
            scribe.retire()

            assertEquals(2, recordingSaver.events.size)
        }
    }

    @Test
    fun saver_cancellation_is_not_reported_to_onSaver() {
        runSuspend {
            val reportedErrors = mutableListOf<Throwable>()
            val cancelingSaver = EntrySaver { throw CancellationException("cancel-delivery") }
            val scribe = scribeWithSavers(
                shelves = listOf(cancelingSaver),
                channel = Channel(capacity = 16),
                onSaver = { _, _, error ->
                    reportedErrors += error
                },
            )

            scribe.newScroll(id = "cancellation-probe").seal(scribe)
            delay(50.milliseconds)
            scribe.retire()

            assertTrue(reportedErrors.isEmpty())
        }
    }
}
