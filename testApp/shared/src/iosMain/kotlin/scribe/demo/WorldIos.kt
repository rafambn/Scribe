package scribe.demo

actual fun platformName() = "iOS"

actual fun currentEpochMillis(): Long = kotlin.system.getTimeMillis()
