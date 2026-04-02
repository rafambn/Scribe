package com.rafambn.scribe

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalExceptionHandlerJvmTest {
    @Test
    fun installUncaughtExceptionHandler_calls_installed_and_previous_handlers() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val invocations = Collections.synchronizedList(mutableListOf<String>())

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            invocations += "previous:${throwable.message}"
        }

        try {
            installUncaughtExceptionHandler { throwable ->
                invocations += "installed:${throwable.message}"
            }

            val thread = Thread {
                throw IllegalStateException("boom")
            }
            thread.start()
            thread.join(2_000)

            assertEquals(
                listOf("installed:boom", "previous:boom"),
                invocations.toList(),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
