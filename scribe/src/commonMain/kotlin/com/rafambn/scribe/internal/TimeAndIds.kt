package com.rafambn.scribe.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private var nextScrollId: Int = 0

internal fun newScrollId(): Int {
    if (nextScrollId == Int.MAX_VALUE) {
        error("Scroll ID space exhausted.")
    }
    nextScrollId += 1
    return nextScrollId
}

@OptIn(ExperimentalTime::class)
internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
