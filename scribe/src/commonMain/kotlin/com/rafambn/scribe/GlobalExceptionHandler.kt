package com.rafambn.scribe

/**
 * Installs one platform-level observer and returns the operation that removes it.
 *
 * The returned function must restore the platform's previous observer when one
 * existed. Ownership of the returned operation remains internal to Scribe.
 */
internal expect fun installUncaughtExceptionHandler(handler: (Throwable) -> Unit): () -> Unit
