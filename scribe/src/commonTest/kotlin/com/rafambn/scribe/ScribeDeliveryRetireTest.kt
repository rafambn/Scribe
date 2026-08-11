package com.rafambn.scribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun archivists_process_the_same_entry_in_parallel() {
        runSuspend {
            val release = CompletableDeferred<Unit>()
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val first = Archivist {
                firstStarted.complete(Unit)
                release.await()
            }
            val second = Archivist {
                secondStarted.complete(Unit)
                release.await()
            }
            val scribe = scribeWithArchivists(listOf(first, second))

            scribe.newScroll(id = "parallel").seal(scribe)
            withTimeout(2_000.milliseconds) {
                firstStarted.await()
                secondStarted.await()
            }

            release.complete(Unit)
            scribe.retire()
        }
    }

    @Test
    fun scroll_events_reach_all_configured_archivists() {
        runSuspend {
            val scrollShelf = RecordingShelf()
            val secondSearcher = RecordingShelf()
            val scribe = scribeWithArchivists(
                shelves = listOf(scrollShelf, secondSearcher),
            )

            scribe.newScroll(id = "scroll-1").seal(scribe)
            scrollShelf.awaitEvents(1)
            secondSearcher.awaitEvents(1)
            scribe.retire()

            assertEquals(1, scrollShelf.events.size)
            assertEquals(
                "scroll-1",
                scrollShelf.events.single()["scroll_id"]?.jsonPrimitive?.content,
            )
            assertEquals("scroll-1", secondSearcher.events.single()["scroll_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun seal_does_not_block_when_sink_is_slow() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate)
            val scribe = scribeWithScrollShelves(
                shelf,
                bufferCapacity = 4,
                bufferOverflow = BufferOverflow.DROP_OLDEST,
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
    fun dismiss_returns_while_inflight_delivery_finishes_cooperatively() {
        runSuspend {
            val gate = CompletableDeferred<Unit>()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val shelf = BlockingShelf(gate, firstWriteStarted)
            val scribe = scribeWithScrollShelves(shelf)

            scribe.newScroll(id = "in-flight").seal(scribe)
            firstWriteStarted.await()
            scribe.dismiss()
            assertFalse(scribe.isProcessing)
            assertTrue(shelf.events.isEmpty())

            gate.complete(Unit)
            shelf.awaitEvents(1)
            assertEquals("in-flight", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
            scribe.retire()
        }
    }

    @Test
    fun dismiss_keeps_intake_open_and_rehire_processes_buffered_entries() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)

            scribe.dismiss()
            assertFalse(scribe.isProcessing)
            scribe.newScroll(id = "after").seal(scribe)
            delay(50.milliseconds)
            assertTrue(shelf.events.isEmpty())

            scribe.hire()
            shelf.awaitEvents(1)
            assertEquals("after", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
            scribe.retire()
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
    fun cancelling_retire_caller_does_not_cancel_retirement() {
        runSuspend {
            coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val firstWriteStarted = CompletableDeferred<Unit>()
                val shelf = BlockingShelf(gate, firstWriteStarted)
                val scribe = scribeWithScrollShelves(shelf)

                scribe.newScroll(id = "survives-cancellation").seal(scribe)
                firstWriteStarted.await()

                val retireJob = launch { scribe.retire() }
                withTimeout(2_000.milliseconds) {
                    while (scribe.isIntakeOpen) yield()
                }
                withTimeout(2_000.milliseconds) { retireJob.cancelAndJoin() }
                assertTrue(retireJob.isCancelled)

                gate.complete(Unit)
                withTimeout(2_000.milliseconds) { scribe.retire() }

                assertEquals(
                    "survives-cancellation",
                    shelf.events.single()["scroll_id"]?.jsonPrimitive?.content,
                )
            }
        }
    }

    @Test
    fun dismiss_called_from_archivist_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val archivist = Archivist {
                scribe.dismiss()
                retired.complete(Unit)
            }
            scribe = scribeWithArchivists(shelves = listOf(archivist))

            scribe.newScroll(id = "retire-1").seal(scribe)
            withTimeout(2_000.milliseconds) { retired.await() }
        }
    }

    @Test
    fun dismiss_called_from_archivist_child_coroutine_does_not_deadlock() {
        runSuspend {
            val retired = CompletableDeferred<Unit>()
            lateinit var scribe: Scribe
            val archivist = Archivist {
                coroutineScope {
                    launch {
                        scribe.dismiss()
                        retired.complete(Unit)
                    }
                }
            }
            scribe = scribeWithArchivists(shelves = listOf(archivist))

            scribe.newScroll(id = "retire-2").seal(scribe)
            withTimeout(2_000.milliseconds) { retired.await() }
        }
    }

    @Test
    fun onArchivistError_is_called_and_other_archivists_continue() {
        runSuspend {
            val events = mutableListOf<Entry>()
            val errors = mutableListOf<Throwable>()
            val failureReported = CompletableDeferred<Unit>()
            val failingArchivist = Archivist { throw IllegalStateException("boom") }
            val recordingArchivist = RecordingShelf()
            val scribe = scribeWithArchivists(
                shelves = listOf(failingArchivist, recordingArchivist),
                onArchivist = { _, entry, error ->
                    events += entry
                    errors += error
                    failureReported.complete(Unit)
                },
            )

            scribe.newScroll(id = "error-1").seal(scribe)
            recordingArchivist.awaitEvents(1)
            failureReported.await()
            scribe.retire()

            assertEquals(1, recordingArchivist.events.size)
            assertEquals(1, events.size)
            assertEquals(1, errors.size)
            val failedEntry = events.single()
            assertEquals("error-1", failedEntry["scroll_id"]?.jsonPrimitive?.content)
            assertEquals("boom", errors.single().message)
        }
    }

    @Test
    fun onArchivistError_callback_failure_does_not_stop_delivery() {
        runSuspend {
            val failingArchivist = Archivist { throw IllegalStateException("boom") }
            val recordingArchivist = RecordingShelf()
            val scribe = scribeWithArchivists(
                shelves = listOf(failingArchivist, recordingArchivist),
                onArchivist = { _, _, _ ->
                    throw IllegalStateException("callback-failed")
                },
            )

            scribe.newScroll(id = "first").seal(scribe)
            scribe.newScroll(id = "second").seal(scribe)
            recordingArchivist.awaitEvents(2)
            scribe.retire()

            assertEquals(2, recordingArchivist.events.size)
        }
    }

    @Test
    fun archivist_cancellation_is_not_reported_to_onArchivist() {
        runSuspend {
            val reportedErrors = mutableListOf<Throwable>()
            val cancelingArchivist = Archivist { throw CancellationException("cancel-delivery") }
            val scribe = scribeWithArchivists(
                shelves = listOf(cancelingArchivist),
                bufferCapacity = 16,
                bufferOverflow = BufferOverflow.SUSPEND,
                onArchivist = { _, _, error ->
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
