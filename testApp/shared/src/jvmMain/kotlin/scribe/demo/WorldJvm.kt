package scribe.demo

actual fun platformName() = "JVM"

actual fun currentEpochMillis(): Long = System.currentTimeMillis()
