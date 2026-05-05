package com.rafambn.scribe

internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            handler(throwable)
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}
