package com.rafambn.scribe

import kotlin.js.js
import kotlin.js.jsTypeOf

internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit =
    if (isNodeRuntime()) {
        installNodeExceptionHandler(handler)
    } else {
        installBrowserExceptionHandler(handler)
    }

private fun isNodeRuntime(): Boolean =
    js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null")

private fun installNodeExceptionHandler(handler: (Throwable) -> Unit): () -> Unit {
    val process: dynamic = js("process")
    val uncaughtExceptionMonitor: (dynamic, dynamic) -> Unit = { reason, origin ->
        val context = if (origin == "unhandledRejection") {
            "Unhandled Node.js promise rejection"
        } else {
            "Uncaught Node.js exception"
        }
        handler(asScribeThrowable(reason, context))
    }

    // uncaughtExceptionMonitor observes failures without adding an
    // uncaughtException listener, so Node keeps its normal termination path.
    process.on("uncaughtExceptionMonitor", uncaughtExceptionMonitor)

    return {
        process.removeListener("uncaughtExceptionMonitor", uncaughtExceptionMonitor)
    }
}

private fun installBrowserExceptionHandler(handler: (Throwable) -> Unit): () -> Unit {
    val global: dynamic = js("globalThis")
    val errorListener: (dynamic) -> Unit = { event ->
        val reason = event?.error ?: event?.message
        handler(asScribeThrowable(reason, "Uncaught browser error"))
    }
    val unhandledRejection: (dynamic) -> Unit = { event ->
        handler(asScribeThrowable(event?.reason, "Unhandled browser promise rejection"))
    }

    global.addEventListener("error", errorListener)
    global.addEventListener("unhandledrejection", unhandledRejection)

    return {
        global.removeEventListener("error", errorListener)
        global.removeEventListener("unhandledrejection", unhandledRejection)
    }
}

private fun asScribeThrowable(value: dynamic, context: String): Throwable =
    try {
        if (value is Throwable) {
            value
        } else {
            IllegalStateException("$context: ${describeJavaScriptValue(value)}")
        }
    } catch (error: Throwable) {
        IllegalStateException("$context: ${describeJavaScriptValue(value)}", error)
    }

private fun describeJavaScriptValue(value: dynamic): String =
    try {
        when (jsTypeOf(value)) {
            "undefined" -> "undefined"
            "object" -> value?.toString() ?: "null"
            else -> value.toString()
        }
    } catch (_: Throwable) {
        "<unprintable JavaScript value>"
    }
