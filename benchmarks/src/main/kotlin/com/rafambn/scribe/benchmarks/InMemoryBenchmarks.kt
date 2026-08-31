package com.rafambn.scribe.benchmarks

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.seal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.measureTime

private const val ITERATIONS = 100_000
private const val COROUTINES = 10

internal suspend fun measureSequentialThroughput() {
    var delivered = 0
    val scribe = benchmarkScribe(Archivist { delivered++ })

    val duration = measureTime {
        repeat(ITERATIONS) { index ->
            val scroll = scribe.newScroll()
            scroll["index"] = JsonPrimitive(index)
            scroll.seal(scribe)
        }
        scribe.retire()
    }

    check(delivered == ITERATIONS) { "Expected $ITERATIONS entries, delivered $delivered." }
    printResults("Sequential in-memory throughput", ITERATIONS, duration)
}

internal suspend fun measureConcurrentThroughput() {
    var delivered = 0
    val scribe = benchmarkScribe(Archivist { delivered++ })

    val duration = measureTime {
        coroutineScope {
            repeat(COROUTINES) { coroutineIndex ->
                launch(Dispatchers.Default) {
                    repeat(ITERATIONS / COROUTINES) { index ->
                        val scroll = scribe.newScroll()
                        scroll["coroutine"] = JsonPrimitive(coroutineIndex)
                        scroll["index"] = JsonPrimitive(index)
                        scroll.seal(scribe)
                    }
                }
            }
        }
        scribe.retire()
    }

    check(delivered == ITERATIONS) { "Expected $ITERATIONS entries, delivered $delivered." }
    printResults("Concurrent in-memory throughput", ITERATIONS, duration)
}
