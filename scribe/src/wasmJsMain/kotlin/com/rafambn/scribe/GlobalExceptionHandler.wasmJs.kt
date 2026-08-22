package com.rafambn.scribe

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit {
    val bridge: (JsAny?, JsAny?) -> Unit = { reason, origin ->
        val context = if (origin?.toString() == "unhandledRejection") {
            "Unhandled WebAssembly JavaScript promise rejection"
        } else {
            "Uncaught WebAssembly JavaScript exception"
        }
        handler(reason.asScribeThrowable(context))
    }
    return installWasmJavaScriptHooks(bridge)
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun installWasmJavaScriptHooks(handler: (JsAny?, JsAny?) -> Unit): () -> Unit = js(
    """(function() {
        const root = globalThis;
        const isNode = typeof process !== 'undefined' &&
            process.versions != null && process.versions.node != null;
        const onUncaught = (value, origin) => handler(value, origin);
        const onBrowserError = event => handler(event.error ?? event.message, null);
        const onBrowserRejection = event => handler(event.reason, null);

        if (isNode && typeof process.on === 'function') {
            process.on('uncaughtExceptionMonitor', onUncaught);
            return () => {
                process.removeListener('uncaughtExceptionMonitor', onUncaught);
            };
        }

        root.addEventListener('error', onBrowserError);
        root.addEventListener('unhandledrejection', onBrowserRejection);
        return () => {
            root.removeEventListener('error', onBrowserError);
            root.removeEventListener('unhandledrejection', onBrowserRejection);
        };
    })()""",
)

@OptIn(ExperimentalWasmJsInterop::class)
private fun JsAny?.asScribeThrowable(context: String): Throwable =
    (this as? Throwable) ?: IllegalStateException("$context: ${this ?: "undefined"}")
