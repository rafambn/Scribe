package com.rafambn.scribe.internal

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun newScrollId(): String = Uuid.random().toString()

@OptIn(ExperimentalTime::class)
internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
