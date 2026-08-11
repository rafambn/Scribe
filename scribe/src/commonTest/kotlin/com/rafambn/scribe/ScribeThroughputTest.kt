package com.rafambn.scribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.TimeSource

class ScribeThroughputTest {

    private class NoOpArchivist : Archivist {
        var count = 0
        override suspend fun write(event: Entry) {
            count++
        }
    }

    @Test
    fun measure_sequential_throughput() = runSuspend {
        val archivist = NoOpArchivist()
        val scribe = scribeWithScrollShelves(
            archivist,
            bufferCapacity = Channel.UNLIMITED,
            bufferOverflow = BufferOverflow.SUSPEND,
        )
        val iterations = 100_000

        val timeSource = TimeSource.Monotonic
        val start = timeSource.markNow()

        repeat(iterations) {
            val scroll = scribe.newScroll()
            scroll["index"] = JsonPrimitive(it)
            scroll.seal(scribe)
        }

        scribe.retire()
        val duration = start.elapsedNow()

        println("Sequential Throughput:")
        printResults(iterations, duration)
    }

    @Test
    fun measure_concurrent_throughput() = runSuspend {
        val archivist = NoOpArchivist()
        val scribe = scribeWithScrollShelves(
            archivist,
            bufferCapacity = Channel.UNLIMITED,
            bufferOverflow = BufferOverflow.SUSPEND,
        )
        val iterations = 100_000
        val coroutines = 10

        val timeSource = TimeSource.Monotonic
        val start = timeSource.markNow()

        coroutineScope {
            repeat(coroutines) { c ->
                launch(Dispatchers.Default) {
                    repeat(iterations / coroutines) { i ->
                        val scroll = scribe.newScroll()
                        scroll["c"] = JsonPrimitive(c)
                        scroll["i"] = JsonPrimitive(i)
                        scroll.seal(scribe)
                    }
                }
            }
        }

        scribe.retire()
        val duration = start.elapsedNow()

        println("Concurrent Throughput ($coroutines coroutines):")
        printResults(iterations, duration)
    }

    private fun printResults(iterations: Int, duration: Duration) {
        val seconds = duration.inWholeMicroseconds / 1_000_000.0
        val opsPerSec = iterations / seconds
        println("  Completed $iterations ops in $duration")
        println("  Throughput: ${opsPerSec.toInt()} ops/sec")
    }
}
