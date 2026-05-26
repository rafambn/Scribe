package scribe.demo.data

import com.rafambn.scribe.Entry
import com.rafambn.scribe.Note
import com.rafambn.scribe.SealedScroll
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

typealias ConsoleRecord = Map<String, JsonElement>

fun consoleRecordFromEntry(
    entry: Entry,
    demoName: String,
    platform: String,
    saverType: String,
    appVersion: String,
    recordedAt: Long,
): ConsoleRecord =
    when (entry) {
        is Note -> linkedMapOf<String, JsonElement>(
            "_timestamp" to JsonPrimitive(recordedAt),
            "event_kind" to JsonPrimitive("note"),
            "demo_name" to JsonPrimitive(demoName),
            "platform" to JsonPrimitive(platform),
            "app_version" to JsonPrimitive(appVersion),
            "saver_type" to JsonPrimitive(saverType),
            "tag" to JsonPrimitive(entry.tag),
            "message" to JsonPrimitive(entry.message),
            "level" to JsonPrimitive(entry.level.name),
            "note_timestamp" to JsonPrimitive(entry.timestamp),
        )

        is SealedScroll -> {
            val payload = linkedMapOf<String, JsonElement>()
            payload["_timestamp"] = JsonPrimitive(recordedAt)
            payload["event_kind"] = JsonPrimitive("scroll")
            payload["demo_name"] = JsonPrimitive(stringField(entry.data, "demo_name") ?: demoName)
            payload["platform"] = JsonPrimitive(platform)
            payload["app_version"] = JsonPrimitive(appVersion)
            payload["saver_type"] = JsonPrimitive(saverType)
            payload["scroll_id"] = JsonPrimitive(stringField(entry.data, "scroll_id") ?: "missing-scroll-id")
            payload["success"] = JsonPrimitive(entry.success)
            stringField(entry.data, "message")?.let { payload["message"] = JsonPrimitive(it) }
            entry.data["order_id"]?.let { payload["order_id"] = it }
                ?: entry.data["ordemId"]?.let { payload["order_id"] = it }
            entry.data.forEach { (key, value) ->
                if (key !in payload) {
                    payload[key] = value
                }
            }
            payload
        }
    }

fun recordSummary(record: ConsoleRecord): String =
    when (payloadEventKind(record)) {
        "note" -> "${record.tag ?: "note"} ${record.level ?: ""}".trim()
        else -> "${record.scroll_id ?: "scroll"} success=${record.success}"
    }

fun payloadEventKind(record: ConsoleRecord): String =
    record["event_kind"]?.jsonPrimitive?.contentOrNull ?: "unknown"

private fun stringField(data: Map<String, JsonElement>, key: String): String? =
    data[key]?.jsonPrimitive?.contentOrNull

val ConsoleRecord.tag: String?
    get() = this["tag"]?.jsonPrimitive?.contentOrNull

val ConsoleRecord.level: String?
    get() = this["level"]?.jsonPrimitive?.contentOrNull

val ConsoleRecord.scroll_id: String?
    get() = this["scroll_id"]?.jsonPrimitive?.contentOrNull

val ConsoleRecord.success: String?
    get() = this["success"]?.jsonPrimitive?.contentOrNull

fun sampleImprint(platform: String): Map<String, JsonElement> = mapOf(
    "service" to JsonPrimitive("scribe-showcase"),
    "environment" to JsonPrimitive("local"),
    "platform" to JsonPrimitive(platform),
)
