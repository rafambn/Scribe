package com.rafambn.scribe

internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        handler(throwable)
        previous?.uncaughtException(thread, throwable)
    }
}
