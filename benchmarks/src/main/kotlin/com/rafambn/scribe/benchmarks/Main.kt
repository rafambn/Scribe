package com.rafambn.scribe.benchmarks

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    measureSequentialThroughput()
    measureConcurrentThroughput()
    measureFileThroughput()
}
