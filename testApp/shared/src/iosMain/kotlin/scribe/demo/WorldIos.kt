package scribe.demo

import kotlin.time.Clock

actual fun platformName() = "iOS"

actual fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
