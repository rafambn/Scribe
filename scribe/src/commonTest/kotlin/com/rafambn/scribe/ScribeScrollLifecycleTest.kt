package com.rafambn.scribe

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScribeScrollLifecycleTest {
    @Test
    fun newScroll_creates_scroll_for_the_given_shelf() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll()
            scroll["method"] = JsonPrimitive("card")
            scroll.seal(success = true)
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(scroll.id, (event.data["scroll_id"] as? JsonPrimitive)?.content)
            assertEquals(JsonPrimitive("card"), event.data["method"])
            assertTrue(event.success)
        }
    }

    @Test
    fun seal_is_idempotent_and_writes_once() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll()

            scroll["gateway"] = JsonPrimitive("stripe")

            scroll.seal(success = false, error = IllegalStateException("fail"))
            scroll.seal(success = true)
            shelf.awaitEvents(2)
            scribe.retire()
            val firstEvent = shelf.events.first()
            val secondEvent = shelf.events.last()
            assertFalse(firstEvent.success)
            assertEquals("fail", firstEvent.errorMessage)
            assertTrue(secondEvent.success)
            assertEquals(JsonPrimitive("stripe"), firstEvent.data["gateway"])
        }
    }

    @Test
    fun two_explicit_scrolls_match_requested_flow() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val paymentService = PaymentService()

            val scroll1 = scribe.newScroll()
            val scroll2 = scribe.newScroll()

            paymentService.pay("order1", scroll1)
            assertFailsWith<IllegalStateException> {
                paymentService.pay("order2", scroll2)
            }
            scroll1.seal(success = true)
            scroll2.seal(success = true)
            shelf.awaitEvents(2)
            scribe.retire()

            val successEvent = shelf.events.firstOrNull { (it.data["scroll_id"] as? JsonPrimitive)?.content == scroll1.id }
            val failureEvent = shelf.events.firstOrNull { (it.data["scroll_id"] as? JsonPrimitive)?.content == scroll2.id }

            assertNotNull(successEvent)
            assertNotNull(failureEvent)
            assertTrue(successEvent.success)
            assertFalse(failureEvent.success)
            assertEquals("order2 failed", failureEvent.errorMessage)
        }
    }

    @Test
    fun generated_scroll_ids_are_unique_uuid_strings() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val ids = (1..500).map { scribe.newScroll().id }

            assertEquals(ids.size, ids.toSet().size)
            assertTrue(ids.all { UUID_REGEX.matches(it) })
        }
    }

    @Test
    fun newScroll_uses_custom_id() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll(id = "session-42")

            scroll["operation"] = JsonPrimitive("sync")
            scroll.seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals("session-42", scroll.id)
            assertEquals("session-42", (event.data["scroll_id"] as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun newScroll_allows_reusing_custom_id_without_internal_tracking() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val first = scribe.newScroll(id = "session-42")
            val second = scribe.newScroll(id = "session-42")

            assertEquals("session-42", first.id)
            assertEquals("session-42", second.id)
        }
    }

    @Test
    fun custom_id_can_be_reused_after_scroll_is_sealed() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)

            scribe.newScroll(id = "session-42").seal()
            shelf.awaitEvents(1)

            val reused = scribe.newScroll(id = "session-42")
            assertEquals("session-42", reused.id)
            scribe.retire()
        }
    }

    @Test
    fun user_managed_map_can_track_scroll_references() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val originalScroll = scribe.newScroll(id = "shared")
            val tracked = mutableMapOf(originalScroll.id to originalScroll)

            originalScroll["stage"] = JsonPrimitive("created")
            originalScroll["status"] = JsonPrimitive("updated")
            val sameScroll = tracked.getValue("shared")

            assertEquals(JsonPrimitive("created"), sameScroll["stage"])
            assertEquals(JsonPrimitive("updated"), sameScroll["status"])
            scribe.retire()
        }
    }
}
