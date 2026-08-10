package com.rafambn.scribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileWriter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

class ScribeFileThroughputTest {

    private lateinit var testFile: File
    private val json = Json { encodeDefaults = true }

    private class FileArchivist(file: File, private val json: Json) : Archivist {
        private val writer = FileWriter(file).buffered()

        override suspend fun write(event: Entry) {
            val line = json.encodeToString(event)
            writer.write(line)
            writer.newLine()
        }

        fun close() {
            writer.flush()
            writer.close()
        }
    }

    @BeforeTest
    fun setup() {
        testFile = File.createTempFile("scribe-throughput", ".log")
    }

    @AfterTest
    fun cleanup() {
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun measure_concurrent_file_throughput() = runBlocking {
        val archivist = FileArchivist(testFile, json)
        val scribe = scribeWithScrollShelves(
            archivist,
            bufferCapacity = Channel.UNLIMITED,
            bufferOverflow = BufferOverflow.SUSPEND,
        )
        val iterations = 50_000
        val coroutines = 10

        println("Starting File Load Test: writing $iterations entries to ${testFile.absolutePath} using $coroutines coroutines...")

        val timeSource = TimeSource.Monotonic
        val start = timeSource.markNow()

        coroutineScope {
            repeat(coroutines) { c ->
                launch(Dispatchers.Default) {
                    repeat(iterations / coroutines) { i ->
                        val scroll = scribe.newScroll()
                        scroll["coroutine"] = JsonPrimitive(c)
                        scroll["index"] = JsonPrimitive(i)
                        scroll["payload"] = JsonPrimitive("Some repetitive logging payload to simulate load " + i)
                        scroll.seal(scribe)
                    }
                }
            }
        }

        scribe.retire()
        archivist.close()
        
        val duration = start.elapsedNow()

        // Verification
        val lines = testFile.readLines()
        assertEquals(iterations, lines.size, "Line count mismatch. Possible data loss.")
        
        // Check for corruption (ensure each line is a valid JSON and belongs to Scribe)
        lines.forEach { line ->
            assertTrue(line.startsWith("{") && line.endsWith("}"), "Interleaved or corrupt line: $line")
            assertTrue(line.contains("scroll_id"), "Metadata missing in line: $line")
        }

        println("File Throughput Results:")
        printResults(iterations, duration)
    }

    private fun printResults(iterations: Int, duration: Duration) {
        val seconds = duration.inWholeMicroseconds / 1_000_000.0
        val opsPerSec = iterations / seconds
        println("  Completed $iterations file writes in $duration")
        println("  Throughput: ${opsPerSec.toInt()} ops/sec")
        println("  Final file size: ${testFile.length() / 1024} KB")
    }
}
