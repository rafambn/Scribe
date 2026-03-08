package com.rafambn.scribe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScribeDataSerializationTest {
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
}
