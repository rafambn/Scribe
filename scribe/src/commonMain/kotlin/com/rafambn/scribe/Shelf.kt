package com.rafambn.scribe

import kotlin.reflect.KClass

/**
 * Contract for persisting [Entry] instances produced by [Scribe].
 */
interface Saver<in T : Entry> {
    /** Exact entry type accepted by this saver, or null to accept every entry. */
    val accepts: KClass<out Entry>?

    /**
     * Handles an emitted event.
     */
    suspend fun write(event: T)
}

/** Creates a saver routed only entries whose runtime type is [T]. */
inline fun <reified T : Entry> Saver(
    crossinline write: suspend (T) -> Unit,
): Saver<T> = object : Saver<T> {
    override val accepts: KClass<out Entry> = T::class

    override suspend fun write(event: T) = write.invoke(event)
}

/**
 * Saver that receives all entry types.
 */
fun interface EntrySaver : Saver<Entry> {
    override val accepts: KClass<out Entry>?
        get() = null
}
