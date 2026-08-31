package com.rafambn.scribe.slf4j

import org.slf4j.Marker
import org.slf4j.event.KeyValuePair
import org.slf4j.event.Level

/** Complete snapshot of a normalized SLF4J call routed to a [Slf4jScribe]. */
data class ScribeLoggingCall(
    val loggerName: String,
    val level: Level,
    val markers: List<Marker>,
    val keyValuePairs: List<KeyValuePair>,
    val messagePattern: String?,
    val arguments: Array<out Any?>?,
    val throwable: Throwable?,
    val mdc: Map<String, String>,
) {
    val marker: Marker?
        get() = markers.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ScribeLoggingCall

        if (loggerName != other.loggerName) return false
        if (level != other.level) return false
        if (markers != other.markers) return false
        if (keyValuePairs != other.keyValuePairs) return false
        if (messagePattern != other.messagePattern) return false
        if (!arguments.contentEquals(other.arguments)) return false
        if (throwable != other.throwable) return false
        if (mdc != other.mdc) return false

        return true
    }

    override fun hashCode(): Int {
        var result = loggerName.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + markers.hashCode()
        result = 31 * result + keyValuePairs.hashCode()
        result = 31 * result + (messagePattern?.hashCode() ?: 0)
        result = 31 * result + (arguments?.contentHashCode() ?: 0)
        result = 31 * result + (throwable?.hashCode() ?: 0)
        result = 31 * result + mdc.hashCode()
        return result
    }
}
