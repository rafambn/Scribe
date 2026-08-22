package com.rafambn.scribe

internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit =
    throw UnsupportedOperationException(
        "Scribe onIgnition is unsupported on wasmWasi because it has no portable global error hook.",
    )
