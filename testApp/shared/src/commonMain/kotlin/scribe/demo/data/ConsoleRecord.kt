package scribe.demo.data

import com.rafambn.scribe.ScrollEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

typealias ConsoleRecord = Map<String, JsonElement>

fun consoleRecordFromEntry(
    entry: ScrollEntry,
    demoName: String,
    platform: String,
    saverType: String,
    appVersion: String,
    recordedAt: Long,
): ConsoleRecord {
    val payload = linkedMapOf<String, JsonElement>()
    payload["_timestamp"] = JsonPrimitive(recordedAt)
    payload["event_kind"] = JsonPrimitive("scroll")
    payload["demo_name"] = JsonPrimitive(stringField(entry, "demo_name") ?: demoName)
    payload["platform"] = JsonPrimitive(platform)
    payload["app_version"] = JsonPrimitive(appVersion)
    payload["saver_type"] = JsonPrimitive(saverType)
    payload["scroll_id"] = JsonPrimitive(stringField(entry, "scroll_id") ?: "missing-scroll-id")
    stringField(entry, "message")?.let { payload["message"] = JsonPrimitive(it) }
    entry["order_id"]?.let { payload["order_id"] = it }
        ?: entry["ordemId"]?.let { payload["order_id"] = it }
    entry.forEach { (key, value) ->
        if (key !in payload) {
            payload[key] = value
        }
    }
    return payload
}

fun recordSummary(record: ConsoleRecord): String =
    record.scroll_id ?: "scroll"

fun payloadEventKind(record: ConsoleRecord): String =
    record["event_kind"]?.jsonPrimitive?.contentOrNull ?: "unknown"

private fun stringField(data: Map<String, JsonElement>, key: String): String? =
    data[key]?.jsonPrimitive?.contentOrNull

val ConsoleRecord.scroll_id: String?
    get() = this["scroll_id"]?.jsonPrimitive?.contentOrNull

fun sampleImprint(platform: String): Map<String, JsonElement> = mapOf(
    "service" to JsonPrimitive("scribe-showcase"),
    "environment" to JsonPrimitive("local"),
    "platform" to JsonPrimitive(platform),
)
