package com.rafambn.scribe

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit {
    var previous: ((Throwable) -> Unit)? = null
    lateinit var installed: (Throwable) -> Unit
    installed = { throwable ->
        try {
            handler(throwable)
        } finally {
            val previousHandler = previous
            if (previousHandler != null && previousHandler !== installed) {
                previousHandler(throwable)
            } else {
                terminateWithUnhandledException(throwable)
            }
        }
    }
    previous = setUnhandledExceptionHook(installed)
    return {
        if (getUnhandledExceptionHook() === installed) {
            setUnhandledExceptionHook(previous)
        }
    }
}
