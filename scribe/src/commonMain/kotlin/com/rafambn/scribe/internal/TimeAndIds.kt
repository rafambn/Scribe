package com.rafambn.scribe.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private var nextScrollId: Int = 0

internal fun newScrollId(): Int {
    if (nextScrollId == Int.MAX_VALUE) {
        nextScrollId = 0
        return nextScrollId
    }
    nextScrollId += 1
    return nextScrollId
}

@OptIn(ExperimentalTime::class)
internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
