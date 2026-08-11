package com.rafambn.scribe.slf4j

import com.rafambn.scribe.Scribe
import com.rafambn.scribe.seal
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.MessageFormatter

/** Process-wide Scribe backend selected at runtime with [ScribeBackend]. */
abstract class Slf4jScribe : Scribe() {

    /** Returns whether a call should be accepted before a scroll is allocated. */
    open fun isEnabled(loggerName: String, level: Level, marker: Marker?): Boolean = true

    /** Converts a normalized SLF4J call into a scroll owned by this Scribe. */
    open fun handleNormalizedLoggingCall(call: ScribeLoggingCall) {
        val message = MessageFormatter.arrayFormat(call.messagePattern, call.arguments).message
        val scroll = newScroll()

        scroll["level"] = JsonPrimitive(call.level.name)
        scroll["logger"] = JsonPrimitive(call.loggerName)
        scroll["message"] = JsonPrimitive(message)

        call.marker?.let {
            scroll["marker"] = JsonPrimitive(it.name)
        }

        call.throwable?.let {
            scroll["exception"] = JsonPrimitive(it.stackTraceToString())
        }

        call.mdc.forEach { (key, value) ->
            if (key !in RESERVED_FIELDS && !scroll.containsKey(key)) {
                scroll[key] = JsonPrimitive(value)
            }
        }

        scroll.seal(this)
    }

    internal fun isLoggingEnabled(loggerName: String, level: Level, marker: Marker?): Boolean =
        isIntakeOpen && isEnabled(loggerName, level, marker)

    internal fun dispatch(call: ScribeLoggingCall) {
        if (isIntakeOpen) {
            handleNormalizedLoggingCall(call)
        }
    }

    private companion object {
        val RESERVED_FIELDS = setOf("scroll_id", "level", "logger", "message", "marker", "exception")
    }
}
