package com.rafambn.scribe

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit) {
    var previous: ((Throwable) -> Unit)? = null
    previous = setUnhandledExceptionHook { throwable ->
        try {
            handler(throwable)
        } finally {
            previous?.invoke(throwable)
        }
    }
}
