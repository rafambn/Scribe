package scribe.demo.scribe

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.Margin
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.Scroll
import com.rafambn.scribe.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import scribe.demo.currentEpochMillis
import scribe.demo.data.sampleImprint
import scribe.demo.platformName
import kotlin.time.Duration.Companion.milliseconds

class AppScribe(onRecord: (Entry) -> Unit) : Scribe() {

    var overflowDelay: Boolean = false

    override val archivists = listOf(
        Archivist { entry ->
            if (entry["tag"]?.jsonPrimitive?.contentOrNull == "archivist_failure") {
                error("Intentional archivist failure from showcase demo")
            }
        },
        Archivist { entry ->
            if (overflowDelay) delay(220.milliseconds)
            onRecord(entry)
        },
    )

    override val imprint = sampleImprint(platformName()) + mapOf(
        "output" to JsonPrimitive("console"),
        "session_kind" to JsonPrimitive("persistent-demo"),
    )

    override val margins = object : Margin {
        override fun header(scroll: Scroll) {
            scroll["started_at"] = JsonPrimitive(currentEpochMillis())
            scroll["platform_session"] = JsonPrimitive(platformName())
        }

        override fun footer(scroll: Scroll) {
            val startedAt = scroll["started_at"]?.jsonPrimitive?.longOrNull ?: return
            val completedAt = currentEpochMillis()
            scroll["completed_at"] = JsonPrimitive(completedAt)
            scroll["elapsed_ms"] = JsonPrimitive(completedAt - startedAt)
        }
    }

    override val onIgnition: (Throwable) -> Unit = { throwable ->
        println("Scribe onIgnition: ${throwable.message ?: throwable}")
    }

    fun close(scope: CoroutineScope) {
        scope.launch {
            runCatching { retire() }
        }
    }
}
