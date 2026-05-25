package scribe.demo

import com.rafambn.scribe.Entry
import com.rafambn.scribe.Note
import com.rafambn.scribe.SealedScroll
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class CheckoutMeta(
    @SerialName("item_count")
    val itemCount: Int,
    @SerialName("subtotal_cents")
    val subtotalCents: Int,
    @SerialName("feature_flag")
    val featureFlag: String,
)

@Serializable
data class SerializationBuyer(
    val id: String,
    val tier: String,
    val email: String,
)

@Serializable
data class SerializationLineItem(
    val sku: String,
    val quantity: Int,
    @SerialName("unit_price_cents")
    val unitPriceCents: Int,
)

@Serializable
data class SerializationPayment(
    val method: String,
    val installments: Int,
    val currency: String,
)

@Serializable
data class SerializationOrderSnapshot(
    @SerialName("order_id")
    val orderId: String,
    val buyer: SerializationBuyer,
    @SerialName("line_items")
    val lineItems: List<SerializationLineItem>,
    val payment: SerializationPayment,
    val tags: List<String>,
    val metadata: Map<String, String>,
)

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
                payload.putIfAbsent(key, value)
            }
            payload
        }
    }

private fun stringField(data: Map<String, JsonElement>, key: String): String? =
    data[key]?.jsonPrimitive?.contentOrNull

data class TimelineItem(
    val title: String,
    val detail: String,
    val payload: String,
    val success: Boolean,
)

data class ShowcaseUiState(
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val statusMessage: String = "Ready to run demo scenarios.",
    val isRetired: Boolean = false,
    val activeScrollIds: List<String> = emptyList(),
    val lastRecord: String = "",
    val outputMessage: String = "Records are written to the application console.",
    val saverErrors: List<String> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val ignitionMessage: String = "The onIgnition hook is wired, but the demo does not crash itself to trigger it.",
)

fun recordSummary(record: ConsoleRecord): String =
    when (payloadEventKind(record)) {
        "note" -> "${record.tag ?: "note"} ${record.level ?: ""}".trim()
        else -> "${record.scroll_id ?: "scroll"} success=${record.success}"
    }

fun payloadEventKind(record: ConsoleRecord): String =
    record["event_kind"]?.jsonPrimitive?.contentOrNull ?: "unknown"

private val ConsoleRecord.tag: String?
    get() = this["tag"]?.jsonPrimitive?.contentOrNull

private val ConsoleRecord.level: String?
    get() = this["level"]?.jsonPrimitive?.contentOrNull

private val ConsoleRecord.scroll_id: String?
    get() = this["scroll_id"]?.jsonPrimitive?.contentOrNull

private val ConsoleRecord.success: String?
    get() = this["success"]?.jsonPrimitive?.contentOrNull

fun sampleImprint(platform: String): Map<String, JsonElement> = mapOf(
    "service" to JsonPrimitive("scribe-showcase"),
    "environment" to JsonPrimitive("local"),
    "platform" to JsonPrimitive(platform),
)
