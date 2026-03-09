package com.rafambn.scribe

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScribeScrollLifecycleTest {
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
            assertTrue(scribe.seekScrolls().isEmpty())
            scribe.retire()

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
            assertTrue(scribe.seekScrolls().isEmpty())
            scribe.retire()

            assertTrue(scroll.isSealed)
            val event = shelf.events.single()
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

            val successEvent = shelf.events.firstOrNull { it.scrollId == scroll1.id }
            val failureEvent = shelf.events.firstOrNull { it.scrollId == scroll2.id }

            assertNotNull(successEvent)
            assertNotNull(failureEvent)
            assertTrue(scribe.seekScrolls().isEmpty())
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
    fun custom_id_can_be_reused_after_scroll_is_sealed() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)

            scribe.unrollScroll(id = "session-42").seal()
            shelf.awaitEvents(1)

            val reused = scribe.unrollScroll(id = "session-42")
            assertEquals("session-42", reused.id)
            scribe.retire()
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
}
