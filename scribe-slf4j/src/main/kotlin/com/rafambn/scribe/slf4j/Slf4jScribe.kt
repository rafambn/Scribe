package com.rafambn.scribe.slf4j

import com.rafambn.scribe.Scribe
import com.rafambn.scribe.seal
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.MessageFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Process-wide Scribe backend selected at runtime with [ScribeBackend]. */
abstract class Slf4jScribe : Scribe() {

    /** Maximum time the JVM shutdown hook waits for accepted entries to drain. */
    protected open val shutdownTimeout: Duration = 5.seconds

    /** Returns whether a call should be accepted before a scroll is allocated. */
    open fun isEnabled(loggerName: String, level: Level, marker: Marker?): Boolean = true

    /** Converts a normalized SLF4J call into a scroll owned by this Scribe. */
    open fun handleNormalizedLoggingCall(call: ScribeLoggingCall) {
        val message = MessageFormatter.arrayFormat(call.messagePattern, call.arguments).message
        val scroll = newScroll()

        scroll["level"] = JsonPrimitive(call.level.name)
        scroll["logger"] = JsonPrimitive(call.loggerName)
        scroll["message"] = message?.let(::JsonPrimitive) ?: JsonNull

        call.markers.firstOrNull()?.let {
            scroll["marker"] = JsonPrimitive(it.name)
        }
        if (call.markers.size > 1) {
            scroll["markers"] = JsonArray(call.markers.map { JsonPrimitive(it.name) })
        }

        call.throwable?.let {
            scroll["exception"] = JsonPrimitive(it.stackTraceToString())
        }

        call.keyValuePairs.forEach { pair ->
            val key = pair.key ?: return@forEach
            if (key !in RESERVED_FIELDS && !scroll.containsKey(key)) {
                scroll[key] = pair.value.toJsonElement()
            }
        }

        call.mdc.forEach { (key, value) ->
            if (key !in RESERVED_FIELDS && !scroll.containsKey(key)) {
                scroll[key] = JsonPrimitive(value)
            }
        }

        scroll.seal(this)
    }

    internal suspend fun retireForShutdown(): Boolean {
        return withTimeoutOrNull(shutdownTimeout) {
            retire()
            true
        } ?: false
    }

    internal fun validateConfiguration() {
        require(shutdownTimeout.isFinite() && shutdownTimeout > Duration.ZERO) {
            "shutdownTimeout must be positive and finite."
        }
    }

    internal fun isLoggingEnabled(loggerName: String, level: Level, marker: Marker?): Boolean =
        isIntakeOpen && isEnabled(loggerName, level, marker)

    internal fun dispatch(call: ScribeLoggingCall) {
        if (isIntakeOpen) {
            handleNormalizedLoggingCall(call)
        }
    }

    private companion object {
        val RESERVED_FIELDS = setOf("scroll_id", "level", "logger", "message", "marker", "markers", "exception")
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
