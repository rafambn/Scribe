package com.rafambn.scribe.benchmarks

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.seal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.io.path.fileSize
import kotlin.time.measureTime

private const val FILE_ITERATIONS = 50_000
private const val FILE_COROUTINES = 10

internal suspend fun measureFileThroughput() {
    val file = Files.createTempFile("scribe-throughput", ".log")
    val writer = Files.newBufferedWriter(file)
    val json = Json { encodeDefaults = true }
    val scribe = benchmarkScribe(
        Archivist { entry ->
            writer.appendLine(json.encodeToString(entry))
        },
    )

    try {
        val duration = measureTime {
            coroutineScope {
                repeat(FILE_COROUTINES) { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(FILE_ITERATIONS / FILE_COROUTINES) { index ->
                            val scroll = scribe.newScroll()
                            scroll["coroutine"] = JsonPrimitive(coroutineIndex)
                            scroll["index"] = JsonPrimitive(index)
                            scroll["payload"] = JsonPrimitive("Repetitive logging payload $index")
                            scroll.seal(scribe)
                        }
                    }
                }
            }
            scribe.retire()
            writer.flush()
        }

        val delivered = Files.lines(file).use { it.count() }
        check(delivered == FILE_ITERATIONS.toLong()) {
            "Expected $FILE_ITERATIONS entries, delivered $delivered."
        }
        printResults(
            name = "Concurrent JSON file throughput",
            iterations = FILE_ITERATIONS,
            duration = duration,
            detail = "File size: ${file.fileSize() / 1_024} KiB",
        )
    } finally {
        if (scribe.isIntakeOpen) {
            scribe.retire()
        }
        writer.close()
        Files.deleteIfExists(file)
    }
}
