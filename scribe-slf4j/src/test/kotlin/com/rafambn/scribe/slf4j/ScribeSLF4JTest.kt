package com.rafambn.scribe.slf4j

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.Entry
import com.rafambn.scribe.seal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@ScribeBackend
internal object TestScribeBackend : Slf4jScribe() {
    val captured = CopyOnWriteArrayList<Entry>()

    override val archivists = listOf(Archivist { entry -> captured.add(entry) })
    override val bufferCapacity: Int = 256
    override val bufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null

    override fun isEnabled(loggerName: String, level: Level, marker: Marker?): Boolean {
        return if (loggerName == "CustomLogger") {
            level == Level.ERROR && marker?.name == "CUSTOM"
        } else {
            true
        }
    }

    override fun handleNormalizedLoggingCall(call: ScribeLoggingCall) {
        if (call.loggerName != "CustomLogger") {
            super.handleNormalizedLoggingCall(call)
            return
        }

        val scroll = newScroll()
        scroll["source"] = JsonPrimitive(call.loggerName)
        scroll["severity"] = JsonPrimitive(call.level.name)
        scroll["template"] = JsonPrimitive(call.messagePattern.orEmpty())
        scroll["argument"] = JsonPrimitive(call.arguments?.firstOrNull().toString())
        scroll["marker"] = JsonPrimitive(call.marker?.name.orEmpty())
        scroll["failure"] = JsonPrimitive(call.throwable?.message.orEmpty())
        scroll["request"] = JsonPrimitive(call.mdc.getValue("requestId"))
        scroll.seal(this)
    }
}

class ScribeSLF4JTest {
    @BeforeTest
    fun resetCapturedEntries() {
        MDC.clear()
        TestScribeBackend.captured.clear()
        TestScribeBackend.hire()
    }

    @Test
    fun `logging buffers until the user hires processing`() = runBlocking {
        val captured = CopyOnWriteArrayList<Entry>()
        val backend = object : Slf4jScribe() {
            override val archivists = listOf(Archivist { entry -> captured.add(entry) })
            override val bufferCapacity: Int = 256
            override val bufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
            override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
        }

        backend.dispatch(
            ScribeLoggingCall(
                loggerName = "BufferedLogger",
                level = Level.INFO,
                markers = emptyList(),
                keyValuePairs = emptyList(),
                messagePattern = "before hire",
                arguments = null,
                throwable = null,
                mdc = emptyMap(),
            ),
        )
        delay(50)
        assertTrue(captured.isEmpty())

        backend.hire()
        withTimeout(5.seconds) {
            while (captured.isEmpty()) delay(10)
        }

        assertEquals("before hire", (captured.single()["message"] as JsonPrimitive).content)
        backend.retire()
    }

    @Test
    fun `provider discovers annotated backend and user starts processing`() = runBlocking {
        val logger = LoggerFactory.getLogger("TestLogger")

        assertTrue(logger.isInfoEnabled)
        logger.info("Hello SLF4J!")
        awaitCapturedEntries(1)

        val entry = TestScribeBackend.captured.single()
        assertEquals("INFO", (entry["level"] as JsonPrimitive).content)
        assertEquals("TestLogger", (entry["logger"] as JsonPrimitive).content)
        assertEquals("Hello SLF4J!", (entry["message"] as JsonPrimitive).content)
    }

    @Test
    fun `fluent logging preserves key values and multiple markers`() = runBlocking {
        val logger = LoggerFactory.getLogger("FluentLogger")
        val firstMarker = MarkerFactory.getMarker("CHECKOUT")
        val secondMarker = MarkerFactory.getMarker("PAYMENT")

        logger.atInfo()
            .addMarker(firstMarker)
            .addMarker(secondMarker)
            .addKeyValue("order_id", "order-42")
            .addKeyValue("attempt", 2)
            .addKeyValue("message", "must not replace the formatted message")
            .log("Order {} accepted", 42)
        awaitCapturedEntries(1)

        val entry = TestScribeBackend.captured.single()
        assertEquals(JsonPrimitive("Order 42 accepted"), entry["message"])
        assertEquals(JsonPrimitive("order-42"), entry["order_id"])
        assertEquals(JsonPrimitive(2), entry["attempt"])
        assertEquals(JsonPrimitive("CHECKOUT"), entry["marker"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("CHECKOUT"), JsonPrimitive("PAYMENT"))),
            entry["markers"],
        )
    }

    @Test
    fun `null message remains structured json null`() = runBlocking {
        val logger = LoggerFactory.getLogger("NullMessageLogger")

        logger.info(null as String?)
        awaitCapturedEntries(1)

        assertEquals(JsonNull, TestScribeBackend.captured.single()["message"])
    }

    @Test
    fun `shutdown drain stops waiting after configured timeout`() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val backend = object : Slf4jScribe() {
            override val archivists = listOf(
                Archivist {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                },
            )
            override val shutdownTimeout = 50.milliseconds
        }

        backend.hire()
        backend.dispatch(
            ScribeLoggingCall(
                loggerName = "ShutdownLogger",
                level = Level.INFO,
                markers = emptyList(),
                keyValuePairs = emptyList(),
                messagePattern = "blocked",
                arguments = null,
                throwable = null,
                mdc = emptyMap(),
            ),
        )
        writeStarted.await()

        withTimeout(1.seconds) {
            assertFalse(backend.retireForShutdown())
        }

        releaseWrite.complete(Unit)
        backend.retire()
    }

    @Test
    fun `mdc context is included without replacing reserved log fields`() = runBlocking {
        val logger = LoggerFactory.getLogger("MdcLogger")

        try {
            MDC.put("requestId", "req-123")
            MDC.put("message", "must not replace the log message")
            MDC.put("scroll_id", "must not replace the scroll id")
            logger.info("Processing request")

            MDC.clear()
            logger.info("Context cleared")
            awaitCapturedEntries(2)
        } finally {
            MDC.clear()
        }

        assertEquals("req-123", (TestScribeBackend.captured[0]["requestId"] as JsonPrimitive).content)
        assertEquals("Processing request", (TestScribeBackend.captured[0]["message"] as JsonPrimitive).content)
        assertFalse(
            (TestScribeBackend.captured[0]["scroll_id"] as JsonPrimitive).content.startsWith("must not"),
        )
        assertTrue("requestId" !in TestScribeBackend.captured[1])
    }

    @Test
    fun `annotated scribe controls levels and normalized call mapping`() = runBlocking {
        val logger = LoggerFactory.getLogger("CustomLogger")
        val marker = MarkerFactory.getMarker("CUSTOM")

        try {
            assertFalse(logger.isDebugEnabled)
            assertFalse(logger.isErrorEnabled)
            assertTrue(logger.isErrorEnabled(marker))
            logger.info("Ignored")
            logger.error("Also ignored")

            MDC.put("requestId", "req-custom")
            logger.error(marker, "Failure {}", 42, IllegalStateException("boom"))
            awaitCapturedEntries(1)
        } finally {
            MDC.clear()
        }

        val entry = TestScribeBackend.captured.single()
        assertEquals("CustomLogger", (entry["source"] as JsonPrimitive).content)
        assertEquals("ERROR", (entry["severity"] as JsonPrimitive).content)
        assertEquals("Failure {}", (entry["template"] as JsonPrimitive).content)
        assertEquals("42", (entry["argument"] as JsonPrimitive).content)
        assertEquals("CUSTOM", (entry["marker"] as JsonPrimitive).content)
        assertEquals("boom", (entry["failure"] as JsonPrimitive).content)
        assertEquals("req-custom", (entry["request"] as JsonPrimitive).content)
    }

    private suspend fun awaitCapturedEntries(count: Int) {
        withTimeout(5.seconds) {
            while (TestScribeBackend.captured.size < count) {
                delay(10)
            }
        }
    }
}
