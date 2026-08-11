package com.rafambn.scribe.slf4j

import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

internal class ScribeLoggerFactory(private val scribe: Slf4jScribe) : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger {
        return loggers.computeIfAbsent(name) { NamedScribeLogger(it, scribe) }
    }
}
