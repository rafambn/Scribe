package com.rafambn.scribe.slf4j

import org.slf4j.Marker
import org.slf4j.MDC
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger

/** Lightweight SLF4J adapter that preserves the name requested from LoggerFactory. */
internal class NamedScribeLogger(
    private val loggerName: String,
    private val scribe: Slf4jScribe,
) : AbstractLogger() {
    override fun getName(): String = loggerName

    override fun isTraceEnabled(): Boolean = isEnabled(Level.TRACE, null)
    override fun isTraceEnabled(marker: Marker?): Boolean = isEnabled(Level.TRACE, marker)

    override fun isDebugEnabled(): Boolean = isEnabled(Level.DEBUG, null)
    override fun isDebugEnabled(marker: Marker?): Boolean = isEnabled(Level.DEBUG, marker)

    override fun isInfoEnabled(): Boolean = isEnabled(Level.INFO, null)
    override fun isInfoEnabled(marker: Marker?): Boolean = isEnabled(Level.INFO, marker)

    override fun isWarnEnabled(): Boolean = isEnabled(Level.WARN, null)
    override fun isWarnEnabled(marker: Marker?): Boolean = isEnabled(Level.WARN, marker)

    override fun isErrorEnabled(): Boolean = isEnabled(Level.ERROR, null)
    override fun isErrorEnabled(marker: Marker?): Boolean = isEnabled(Level.ERROR, marker)

    private fun isEnabled(level: Level, marker: Marker?): Boolean =
        scribe.isLoggingEnabled(loggerName, level, marker)

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        scribe.dispatch(
            ScribeLoggingCall(
                loggerName = loggerName,
                level = level,
                marker = marker,
                messagePattern = messagePattern,
                arguments = arguments,
                throwable = throwable,
                mdc = MDC.getCopyOfContextMap()?.toMap().orEmpty(),
            ),
        )
    }

    override fun getFullyQualifiedCallerName(): String = javaClass.name
}
