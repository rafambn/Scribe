package scribe.demo.scribe

import com.rafambn.scribe.EntrySaver
import com.rafambn.scribe.Margin
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.Scroll
import com.rafambn.scribe.ScrollEntry
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

class AppScribe(onRecord: (ScrollEntry) -> Unit) : Scribe() {

    var overflowDelay: Boolean = false

    override val shelves = listOf(
        EntrySaver { entry ->
            if (entry is ScrollEntry && entry["tag"]?.jsonPrimitive?.contentOrNull == "saver_failure") {
                error("Intentional saver failure from showcase demo")
            }
        },
        EntrySaver { entry ->
            if (overflowDelay) delay(220.milliseconds)
            if (entry is ScrollEntry) onRecord(entry)
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
