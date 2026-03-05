package com.rafambn.scribe

internal expect fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit)
