package com.rafambn.scribe

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScribeRuntimeTest {
    @Test
    fun startScroll_creates_scroll_for_the_given_shelf() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val scroll = scribe.startScroll()

        assertSame(scribe, scroll.context)
        assertEquals(1, scribe.scrolls.size)
        assertSame(scroll, scribe.scrolls.single())

        runSuspend {
            scroll.put("method", "card")
            scroll.footnote("processing payment")
            scroll.seal(success = true)
        }

        assertEquals(1, shelf.events.size)
        val event = shelf.events.single()
        assertEquals(scroll.id, event.scrollId)
        assertEquals("card", event.data["method"])
        assertEquals(listOf("processing payment"), event.footnotes)
        assertTrue(event.success)
    }

    @Test
    fun seal_is_idempotent_and_writes_once() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val scroll = scribe.startScroll()

        scroll.put("gateway", "stripe")
        scroll.footnote("charging card")
        assertFalse(scroll.isSealed)

        runSuspend {
            scroll.seal(success = false, error = IllegalStateException("fail"))
            scroll.seal(success = true)
        }

        assertTrue(scroll.isSealed)
        assertEquals(1, shelf.events.size)

        val event = shelf.events.single()
        assertEquals(scroll.id, event.scrollId)
        assertEquals(false, event.success)
        assertEquals("fail", event.errorMessage)
        assertEquals("stripe", event.data["gateway"])
        assertEquals(listOf("charging card"), event.footnotes)
    }

    @Test
    fun two_explicit_scrolls_match_requested_flow() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val paymentService = PaymentService()

        val scroll1 = scribe.startScroll()
        val scroll2 = scribe.startScroll()

        runSuspend {
            paymentService.pay("order1", scroll1)

            assertFailsWith<IllegalStateException> {
                paymentService.pay("order2", scroll2)
            }

            scroll1.seal(success = true)
            scroll2.seal(success = true) // ignored: already sealed in failure path
        }

        assertEquals(2, shelf.events.size)

        val successEvent = shelf.events.firstOrNull { it.scrollId == scroll1.id }
        val failureEvent = shelf.events.firstOrNull { it.scrollId == scroll2.id }

        assertNotNull(successEvent)
        assertNotNull(failureEvent)
        assertEquals(2, scribe.scrolls.size)

        assertTrue(successEvent.success)
        assertEquals(null, successEvent.errorMessage)
        assertEquals(scroll1.id, successEvent.data["scrollId"])
        assertEquals("stripe", successEvent.data["gateway"])
        assertEquals(listOf("charging card"), successEvent.footnotes)

        assertFalse(failureEvent.success)
        assertEquals(scroll2.id, failureEvent.data["scrollId"])
        assertEquals("gateway_call", failureEvent.data["error_stage"])
        assertEquals("order2 failed", failureEvent.errorMessage)
        assertEquals(listOf("charging card"), failureEvent.footnotes)
    }

    @Test
    fun putSerializable_stores_serializable_object_value() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val scroll = scribe.startScroll()

        runSuspend {
            scroll.put("meta", GatewayMeta(retries = 2))
            scroll.seal()
        }

        val event = shelf.events.single()
        assertEquals(GatewayMeta(retries = 2), event.data["meta"])
    }

    @Test
    fun put_throws_for_custom_object_without_serializer() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val scroll = scribe.startScroll()

        assertFailsWith<IllegalArgumentException> {
            scroll.put("meta", NonSerializableMeta(retries = 2))
        }
    }

    @Test
    fun generated_scroll_ids_are_unique_ints() {
        val shelf = RecordingShelf()
        val scribe = Scribe(shelf)
        val ids = (1..500).map { scribe.startScroll().id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }

    private class PaymentService {
        suspend fun pay(orderId: String, scroll: Scroll) {
            try {
                scroll.put("scrollId", scroll.id)
                scroll.footnote("charging card")
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

        override suspend fun write(event: SealedScrollEvent) {
            events += event
        }
    }

}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null

    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )

    return outcome!!.getOrThrow()
}
