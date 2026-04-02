package com.rafambn.scribe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScribeDataSerializationTest {
    @Test
    fun writeSerializable_stores_serializable_object_value() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll()

            scroll["meta"] = Json.encodeToJsonElement(GatewayMeta.serializer(), GatewayMeta(retries = 2))
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
            val scroll = scribe.newScroll()

            scroll["message"] = JsonPrimitive("accepted")
            scroll["attempt"] = JsonPrimitive(3)
            scroll["retry"] = JsonPrimitive(false)
            scroll["meta"] = Json.encodeToJsonElement(GatewayMeta.serializer(), GatewayMeta(retries = 2))
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
    fun map_can_store_serializable_json_value() {
        runSuspend {
            val scribe = scribeWithScrollShelves(RecordingShelf())
            val scroll = scribe.newScroll()

            scroll["meta"] = Json.encodeToJsonElement(GatewayMeta.serializer(), GatewayMeta(retries = 2))
            assertEquals(JsonObject(mapOf("retries" to JsonPrimitive(2))), scroll["meta"])
        }
    }

    @Test
    fun map_accepts_numeric_values() {
        runSuspend {
            val scribe = scribeWithScrollShelves(RecordingShelf())
            val scroll = scribe.newScroll()

            scroll["latency_ms"] = JsonPrimitive(123)
            assertEquals(JsonPrimitive(123), scroll["latency_ms"])
        }
    }

    @Test
    fun scroll_read_and_erase_work() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll()

            scroll["key"] = JsonPrimitive("value")
            assertEquals(JsonPrimitive("value"), scroll["key"])
            val removed = scroll.remove("key")
            assertEquals(JsonPrimitive("value"), removed)
            assertNull(scroll["key"])
            scribe.retire()
        }
    }

    @Test
    fun erase_after_seal_mutates_same_backing_map() {
        runSuspend {
            val shelf = RecordingShelf()
            val scribe = scribeWithScrollShelves(shelf)
            val scroll = scribe.newScroll()

            scroll["key"] = JsonPrimitive("value")
            scroll.seal()
            assertEquals(JsonPrimitive("value"), scroll.remove("key"))
            shelf.awaitEvents(1)
            scribe.retire()

            val event = shelf.events.single()
            assertNull(event.data["key"])
        }
    }
}
