package com.rafambn.scribe.slf4j

import io.github.classgraph.ClassGraph
import kotlinx.coroutines.runBlocking
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/** SLF4J provider backed by the single [Slf4jScribe] object annotated with [ScribeBackend]. */
class ScribeServiceProvider : SLF4JServiceProvider {
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = BasicMDCAdapter()
    private lateinit var scribe: Slf4jScribe
    private lateinit var loggerFactory: ILoggerFactory

    override fun initialize() {
        check(!::scribe.isInitialized) { "Scribe SLF4J provider is already initialized." }
        val createdScribe = discoverScribe()
        createdScribe.validateConfiguration()
        try {
            registerShutdownHook(createdScribe)
        } catch (error: Throwable) {
            runCatching { runBlocking { createdScribe.retire() } }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        scribe = createdScribe
        loggerFactory = ScribeLoggerFactory(createdScribe)
    }

    override fun getLoggerFactory(): ILoggerFactory {
        check(::loggerFactory.isInitialized) { "Scribe SLF4J provider has not been initialized." }
        return loggerFactory
    }

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = "2.0.99"

    private fun discoverScribe(): Slf4jScribe {
        val backendClasses = ClassGraph()
            .enableAnnotationInfo()
            .scan()
            .use { scan ->
                scan.getClassesWithAnnotation(ScribeBackend::class.java.name)
                    .map { it.loadClass() }
            }

        check(backendClasses.isNotEmpty()) {
            "No Scribe SLF4J backend was found. Annotate exactly one Kotlin object extending " +
                "Slf4jScribe with @ScribeBackend."
        }
        check(backendClasses.size == 1) {
            "Multiple Scribe SLF4J backends were found: ${backendClasses.joinToString { it.name }}. " +
                "Annotate exactly one Kotlin object with @ScribeBackend."
        }

        val backendClass = backendClasses.single()
        check(Slf4jScribe::class.java.isAssignableFrom(backendClass)) {
            "@ScribeBackend class ${backendClass.name} must extend Slf4jScribe."
        }

        val instance = runCatching { backendClass.getField("INSTANCE").get(null) }
            .getOrElse { error ->
                throw IllegalStateException(
                    "@ScribeBackend class ${backendClass.name} must be a Kotlin object.",
                    error,
                )
            }
        return instance as Slf4jScribe
    }

    private fun registerShutdownHook(scribe: Slf4jScribe) {
        val hook = Thread(
            { runBlocking { scribe.retireForShutdown() } },
            "scribe-slf4j-shutdown",
        )
        Runtime.getRuntime().addShutdownHook(hook)
    }
}
