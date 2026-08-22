package com.rafambn.scribe

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Owns the process-wide exception observer while keeping callback ownership
 * with individual [Scribe] instances.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object IgnitionRegistry {
    private class Subscription(val callback: (Throwable) -> Unit)
    private data class State(
        val subscriptions: List<Subscription>,
        val uninstall: (() -> Unit)?,
    )

    private val state = AtomicReference(State(emptyList(), null))
    private val changingPlatformHandler = AtomicBoolean(false)

    fun register(callback: (Throwable) -> Unit): () -> Unit {
        val subscription = Subscription(callback)
        while (true) {
            val current = state.load()
            if (current.subscriptions.isNotEmpty()) {
                val next = current.copy(subscriptions = current.subscriptions + subscription)
                if (state.compareAndSet(current, next)) {
                    return { unregister(subscription) }
                }
                continue
            }

            if (!changingPlatformHandler.compareAndSet(false, true)) {
                continue
            }

            try {
                if (state.load().subscriptions.isNotEmpty()) {
                    continue
                }
                val uninstall = installUncaughtExceptionHandler(::dispatch)
                val next = State(listOf(subscription), uninstall)
                if (state.compareAndSet(current, next)) {
                    return { unregister(subscription) }
                }
                uninstall()
            } finally {
                changingPlatformHandler.store(false)
            }
        }
    }

    private fun unregister(subscription: Subscription) {
        while (true) {
            val current = state.load()
            if (subscription !in current.subscriptions) {
                return
            }

            val remaining = current.subscriptions.filterNot { it === subscription }
            if (remaining.isNotEmpty()) {
                if (state.compareAndSet(current, current.copy(subscriptions = remaining))) {
                    return
                }
                continue
            }

            if (!changingPlatformHandler.compareAndSet(false, true)) {
                continue
            }

            try {
                val latest = state.load()
                if (subscription !in latest.subscriptions) {
                    return
                }
                if (latest.subscriptions.size != 1) {
                    continue
                }
                if (state.compareAndSet(latest, State(emptyList(), null))) {
                    latest.uninstall?.invoke()
                    return
                }
            } finally {
                changingPlatformHandler.store(false)
            }
        }
    }

    private fun dispatch(throwable: Throwable) {
        val snapshot = state.load().subscriptions
        snapshot.forEach { subscription ->
            try {
                subscription.callback(throwable)
            } catch (_: Throwable) {
                // A callback must not prevent another active callback or the
                // platform's original handler from observing the failure.
            }
        }
    }
}

internal fun registerGlobalIgnitionHandler(callback: (Throwable) -> Unit): () -> Unit =
    IgnitionRegistry.register(callback)
