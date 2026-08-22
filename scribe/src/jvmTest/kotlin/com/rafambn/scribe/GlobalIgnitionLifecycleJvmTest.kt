package com.rafambn.scribe

import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalIgnitionLifecycleJvmTest {
    @Test
    fun active_scribes_receive_one_notification_and_retire_unregisters_them() = runBlocking {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val previous = Thread.UncaughtExceptionHandler { _, throwable ->
            calls += "previous:${throwable.message}"
        }
        Thread.setDefaultUncaughtExceptionHandler(previous)

        val first = scribeWithScrollShelves(
            Archivist { },
            onIgnition = { calls += "first:${it.message}" },
        )
        val second = scribeWithScrollShelves(
            Archivist { },
            onIgnition = { calls += "second:${it.message}" },
        )

        try {
            first.hire()
            val thread = Thread { throw IllegalStateException("before-retire") }
            thread.start()
            thread.join(2_000)
            assertEquals(
                listOf("first:before-retire", "second:before-retire", "previous:before-retire"),
                calls.toList(),
            )

            first.retire()
            first.retire()
            calls.clear()
            val afterFirstRetire = Thread { throw IllegalStateException("after-first-retire") }
            afterFirstRetire.start()
            afterFirstRetire.join(2_000)
            assertEquals(
                listOf("second:after-first-retire", "previous:after-first-retire"),
                calls.toList(),
            )

            second.retire()
            calls.clear()
            val afterAllRetire = Thread { throw IllegalStateException("after-all-retire") }
            afterAllRetire.start()
            afterAllRetire.join(2_000)
            assertEquals(listOf("previous:after-all-retire"), calls.toList())
        } finally {
            if (first.isIntakeOpen) first.retire()
            if (second.isIntakeOpen) second.retire()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun a_throwing_callback_does_not_block_other_callbacks_or_previous_handler() = runBlocking {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            calls += "previous:${throwable.message}"
        }
        val throwing = scribeWithScrollShelves(
            Archivist { },
            onIgnition = {
                calls += "throwing:${it.message}"
                error("callback-failed")
            },
        )
        val healthy = scribeWithScrollShelves(
            Archivist { },
            onIgnition = { calls += "healthy:${it.message}" },
        )

        try {
            val thread = Thread { throw IllegalStateException("isolated") }
            thread.start()
            thread.join(2_000)
            assertEquals(
                listOf("throwing:isolated", "healthy:isolated", "previous:isolated"),
                calls.toList(),
            )
        } finally {
            if (throwing.isIntakeOpen) throwing.retire()
            if (healthy.isIntakeOpen) healthy.retire()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
