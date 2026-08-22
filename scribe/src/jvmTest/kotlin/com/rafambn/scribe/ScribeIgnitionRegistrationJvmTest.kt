package com.rafambn.scribe

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScribeIgnitionRegistrationJvmTest {
    @Test
    fun concurrent_hires_retry_when_the_registration_fails() {
        val firstRegistrationStarted = CountDownLatch(1)
        val releaseFirstRegistration = CountDownLatch(1)
        val bothHiresStarted = CountDownLatch(2)
        val registrationCalls = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        mockkStatic(::registerGlobalIgnitionHandler)
        every { registerGlobalIgnitionHandler(any()) } answers {
            if (registrationCalls.incrementAndGet() == 1) {
                firstRegistrationStarted.countDown()
                releaseFirstRegistration.await(2, TimeUnit.SECONDS)
            }
            throw UnsupportedOperationException("registration failed")
        }
        val scribe = object : Scribe() {
            override val archivists: List<Archivist>
                get() {
                    bothHiresStarted.countDown()
                    return listOf(Archivist {})
                }

            override val onIgnition: ((Throwable) -> Unit) = {}
        }
        val first = Thread {
            try {
                scribe.hire()
            } catch (error: Throwable) {
                failures += error
            }
        }
        val second = Thread {
            try {
                scribe.hire()
            } catch (error: Throwable) {
                failures += error
            }
        }

        try {
            first.start()
            assertTrue(firstRegistrationStarted.await(2, TimeUnit.SECONDS))
            second.start()
            assertTrue(bothHiresStarted.await(2, TimeUnit.SECONDS))
            releaseFirstRegistration.countDown()

            first.join(2_000)
            second.join(2_000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertEquals(2, failures.size)
            assertTrue(failures.all { it is UnsupportedOperationException })

            val retry = runCatching { scribe.hire() }.exceptionOrNull()
            assertTrue(retry is UnsupportedOperationException)
            assertEquals(3, registrationCalls.get())
        } finally {
            releaseFirstRegistration.countDown()
            first.join(2_000)
            second.join(2_000)
            unmockkStatic(::registerGlobalIgnitionHandler)
            runBlocking {
                if (scribe.isIntakeOpen) {
                    scribe.retire()
                }
            }
        }
    }
}
