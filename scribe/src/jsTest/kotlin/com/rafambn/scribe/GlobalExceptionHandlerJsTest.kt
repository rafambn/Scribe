package com.rafambn.scribe

import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertTrue

class GlobalExceptionHandlerJsTest {
    @Test
    fun node_global_failures_reach_the_callback_and_non_throwables_are_wrapped() = runSuspend {
        if (!isNodeRuntime()) return@runSuspend

        val calls = mutableListOf<String>()
        val scribe = scribeWithScrollShelves(
            Archivist { },
            onIgnition = { calls += it.message.orEmpty() },
        )

        try {
            emitNodeFailure("uncaught-value", "uncaughtException")
            emitNodeFailure("rejected-value", "unhandledRejection")
            emitUnhandledRejection("ignored-value")
            assertTrue(calls.size == 2)
            assertTrue(calls.any { it.contains("Uncaught Node.js") })
            assertTrue(calls.any { it.contains("Unhandled Node.js") })
            assertTrue(calls.all { it.contains("value") })
        } finally {
            scribe.retire()
        }
    }

    private fun isNodeRuntime(): Boolean =
        js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null")

    private fun emitNodeFailure(value: String, origin: String?) {
        val process: dynamic = js("process")
        process.emit("uncaughtExceptionMonitor", value, origin)
    }

    private fun emitUnhandledRejection(value: String) {
        val process: dynamic = js("process")
        process.emit("unhandledRejection", value)
    }
}
