package com.rafambn.scribe

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit) {
    val previous = setUnhandledExceptionHook { throwable ->
        handler(throwable)
    }
    // Re-install, wrapping previous hook
    setUnhandledExceptionHook { throwable ->
        handler(throwable)
        previous?.invoke(throwable)
    }
}
