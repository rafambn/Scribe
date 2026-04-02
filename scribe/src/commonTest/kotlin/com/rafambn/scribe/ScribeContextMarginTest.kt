package com.rafambn.scribe

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ScribeContextMarginTest {
    @Test
    fun unrollScroll_includes_context_data_in_event() {
        runSuspend {
            val shelf = RecordingShelf()
            val imprint = mapOf(
                "service" to JsonPrimitive("mobile-app"),
                "environment" to JsonPrimitive("production"),
            )
            val scribe = scribeWithScrollShelves(shelf, imprint = imprint)

            scribe.unrollScroll().seal()
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertEquals(JsonPrimitive("test-service"), event.context["service"])
            assertEquals(JsonPrimitive("test"), event.context["environment"])
        }
    }

    @Test
    fun all_scrolls_share_same_context() {
        runSuspend {
            val shelf = RecordingShelf()
            val imprint = mapOf("region" to JsonPrimitive("us-east"))
            val scribe = scribeWithScrollShelves(shelf, imprint = imprint)

            scribe.unrollScroll(id = "first").seal()
            scribe.unrollScroll(id = "second").seal()
            shelf.awaitEvents(2)
            scribe.retire()

            val firstEvent = shelf.events.firstOrNull { it.scrollId == "first" }
            val secondEvent = shelf.events.firstOrNull { it.scrollId == "second" }

            assertNotNull(firstEvent)
            assertNotNull(secondEvent)
            assertEquals(JsonPrimitive("test-region"), firstEvent.context["region"])
            assertEquals(JsonPrimitive("test-region"), secondEvent.context["region"])
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
            assertEquals(JsonPrimitive("test-region"), event.context["region"])
            assertEquals(JsonPrimitive("ap-south"), event.data["region"])
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

            scribe.unrollScroll().seal()
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

            scribe.unrollScroll().seal()
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
                    if (scroll.read("_startTime") != null) {
                        scroll.erase("_startTime")
                        scroll.writeNumber("elapsedMs", 500L)
                    }
                }
            }
            val scribe = scribeWithScrollShelves(shelf, margins = elapsedMargin)

            scribe.unrollScroll().seal()
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
                override fun header(scroll: Scroll) {
                    calls.add("header")
                }

                override fun footer(scroll: Scroll) {
                    calls.add("footer")
                }
            }
            val scribe = scribeWithScrollShelves(shelf, margins = margin)

            scribe.unrollScroll().seal()
            scribe.retire()

            assertEquals(listOf("header", "footer"), calls)
        }
    }
}
