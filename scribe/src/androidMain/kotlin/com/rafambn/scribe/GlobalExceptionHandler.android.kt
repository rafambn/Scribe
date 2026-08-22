package com.rafambn.scribe

internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val installed = Thread.UncaughtExceptionHandler { thread, throwable ->
        try {
            handler(throwable)
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
    Thread.setDefaultUncaughtExceptionHandler(installed)
    return {
        if (Thread.getDefaultUncaughtExceptionHandler() === installed) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
