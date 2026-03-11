package scribe.demo

import com.rafambn.scribe.Entry
import com.rafambn.scribe.Note
import com.rafambn.scribe.SealedScroll
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class ConnectionState {
    IDLE,
    CHECKING,
    CONNECTED,
    FAILED,
}

@Serializable
data class CheckoutMeta(
    val itemCount: Int,
    val subtotalCents: Int,
    val featureFlag: String,
)

@Serializable
data class OpenObserveRecord(
    @SerialName("event_kind")
    val eventKind: String,
    @SerialName("_timestamp")
    val timestamp: Long,
    @SerialName("demo_name")
    val demoName: String,
    @SerialName("platform")
    val platform: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("saver_type")
    val saverType: String,
    @SerialName("tag")
    val tag: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("level")
    val level: String? = null,
    @SerialName("note_timestamp")
    val noteTimestamp: Long? = null,
    @SerialName("scroll_id")
    val scrollId: String? = null,
    @SerialName("success")
    val success: Boolean? = null,
    @SerialName("error_message")
    val errorMessage: String? = null,
    @SerialName("context")
    val context: Map<String, JsonElement>? = null,
    @SerialName("data")
    val data: Map<String, JsonElement>? = null,
) {
    companion object {
        fun fromEntry(
            entry: Entry,
            demoName: String,
            platform: String,
            saverType: String,
            appVersion: String,
            uploadedAt: Long,
        ): OpenObserveRecord =
            when (entry) {
                is Note -> OpenObserveRecord(
                    eventKind = "note",
                    timestamp = uploadedAt,
                    demoName = demoName,
                    platform = platform,
                    appVersion = appVersion,
                    saverType = saverType,
                    tag = entry.tag,
                    message = entry.message,
                    level = entry.level.name,
                    noteTimestamp = entry.timestamp,
                )

                is SealedScroll -> OpenObserveRecord(
                    eventKind = "scroll",
                    timestamp = uploadedAt,
                    demoName = demoName,
                    platform = platform,
                    appVersion = appVersion,
                    saverType = saverType,
                    scrollId = entry.scrollId,
                    success = entry.success,
                    errorMessage = entry.errorMessage,
                    context = entry.context,
                    data = entry.data,
                )
            }
    }
}

data class TimelineItem(
    val title: String,
    val detail: String,
    val payload: String,
    val success: Boolean,
)

data class ShowcaseUiState(
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val connectionMessage: String = "OpenObserve has not been checked yet.",
    val statusMessage: String = "Ready to run demo scenarios.",
    val isRetired: Boolean = false,
    val streamName: String = "scribe_demo",
    val activeScrollIds: List<String> = emptyList(),
    val lastPayload: String = "",
    val lastUploadMessage: String = "No uploads yet.",
    val saverErrors: List<String> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val ignitionMessage: String = "The onIgnition hook is wired, but the demo does not crash itself to trigger it.",
)

fun recordSummary(record: OpenObserveRecord): String =
    when (record.eventKind) {
        "note" -> "${record.tag ?: "note"} ${record.level ?: ""}".trim()
        else -> "${record.scrollId ?: "scroll"} success=${record.success}"
    }

fun sampleImprint(platform: String): Map<String, JsonElement> = mapOf(
    "service" to JsonPrimitive("scribe-showcase"),
    "environment" to JsonPrimitive("local"),
    "platform" to JsonPrimitive(platform),
)
