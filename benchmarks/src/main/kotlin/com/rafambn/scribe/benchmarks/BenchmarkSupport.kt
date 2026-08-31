package com.rafambn.scribe.benchmarks

import com.rafambn.scribe.Archivist
import com.rafambn.scribe.Scribe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

internal fun benchmarkScribe(archivist: Archivist): Scribe = object : Scribe() {
    override val archivists = listOf(archivist)
    override val bufferCapacity = Channel.UNLIMITED
    override val bufferOverflow = BufferOverflow.SUSPEND
}.also { it.hire() }

internal fun printResults(
    name: String,
    iterations: Int,
    duration: Duration,
    detail: String? = null,
) {
    val seconds = duration.inWholeMicroseconds / 1_000_000.0
    val operationsPerSecond = iterations / seconds
    println(name)
    println("  Completed $iterations entries in $duration")
    println("  Throughput: ${operationsPerSecond.toInt()} entries/second")
    detail?.let { println("  $it") }
}
