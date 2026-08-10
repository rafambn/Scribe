package com.rafambn.scribe

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ScribeIndependentLifecycleTest {
    @Test
    fun input_starts_enabled_while_processing_starts_paused() = runSuspend {
        val shelf = RecordingShelf()
        val scribe = scribeWithScrollShelves(shelf, startProcessing = false)

        assertTrue(scribe.isIntakeOpen)
        assertFalse(scribe.isProcessing)

        scribe.newScroll(id = "buffered-before-hire").seal(scribe)
        delay(50.milliseconds)
        assertTrue(shelf.events.isEmpty())

        scribe.hire()
        shelf.awaitEvents(1)
        assertEquals(
            "buffered-before-hire",
            shelf.events.single()["scroll_id"]?.jsonPrimitive?.content,
        )
        scribe.retire()
    }

    @Test
    fun input_can_be_disabled_and_enabled_without_stopping_processing() = runSuspend {
        val shelf = RecordingShelf()
        val scribe = scribeWithScrollShelves(shelf)

        scribe.closeIntake()
        assertFalse(scribe.isIntakeOpen)
        assertTrue(scribe.isProcessing)
        scribe.newScroll(id = "rejected").seal(scribe)

        scribe.openIntake()
        scribe.newScroll(id = "accepted").seal(scribe)
        shelf.awaitEvents(1)

        assertEquals("accepted", shelf.events.single()["scroll_id"]?.jsonPrimitive?.content)
        scribe.retire()
    }

    @Test
    fun retire_drains_entries_even_when_job_is_dismissed() = runSuspend {
        val shelf = RecordingShelf()
        val scribe = scribeWithScrollShelves(shelf, startProcessing = false)

        scribe.newScroll(id = "first").seal(scribe)
        scribe.newScroll(id = "second").seal(scribe)

        withTimeout(2_000.milliseconds) { scribe.retire() }

        assertEquals(listOf("first", "second"), shelf.events.map { it["scroll_id"]?.jsonPrimitive?.content })
        assertFalse(scribe.isIntakeOpen)
        assertFalse(scribe.isProcessing)
    }
}
