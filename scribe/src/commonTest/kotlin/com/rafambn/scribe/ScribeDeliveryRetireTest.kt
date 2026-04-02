package com.rafambn.scribe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScribeDeliveryRetireTest {
    @Test
    fun events_are_dispatched_to_multiple_sinks() {
        runSuspend {
            val shelf1 = RecordingShelf()
            val shelf2 = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf1, shelf2)

            scribe.unrollScroll(id = "scroll-a").seal()
            shelf1.awaitEvents(1)
            shelf2.awaitEvents(1)
            scribe.retire()

            assertEquals(1, shelf1.events.size)
            assertEquals(1, shelf2.events.size)
        }
    }

    @Test
    fun routes_can_select_notes_scrolls_or_both() {
        runSuspend {
            val scrollShelf = RecordingShelf()
            val noteSaver = RecordingNoteSaver()
            val allSaver = RecordingEntrySaver()
            val scribe = scribeWithSavers(
                shelves = listOf(scrollShelf, noteSaver, allSaver),
            )

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 100L)
            scribe.unrollScroll(id = "scroll-1").seal()
            scrollShelf.awaitEvents(1)
            noteSaver.awaitEvents(1)
            allSaver.awaitEvents(2)
            scribe.retire()

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

            scribe.unrollScroll(id = "slow").seal()
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
            scribe.retire()
            assertEquals(0, shelf.events.size)

            gate.complete(Unit)
            shelf.awaitEvents(1)
            assertEquals("in-flight", shelf.events.single().scrollId)
        }
    }

    @Test
    fun flingNote_returns_false_after_retire() {
        runSuspend {
            val scribe = scribeWithScrollShelves(RecordingShelf())

            scribe.retire()
            assertFailsWith<IllegalStateException> {
                scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 123L)
            }
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
            withTimeout(2_000) { retireJob.join() }
            retireScope.cancel()

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
            scribe = scribeWithSavers(shelves = listOf(saver))

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 1L)
            withTimeout(2_000) { retired.await() }
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
            scribe = scribeWithSavers(shelves = listOf(saver))

            scribe.flingNote(tag = "payments", message = "started", level = Urgency.INFO, timestamp = 2L)
            withTimeout(2_000) { retired.await() }
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
}
