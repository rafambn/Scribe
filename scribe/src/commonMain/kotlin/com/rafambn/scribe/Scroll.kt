package com.rafambn.scribe

import com.rafambn.scribe.internal.nowEpochMs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf

class Scroll(
    val id: Int,
    val context: Scribe,
    private val startedAtEpochMs: Long,
) {
    private val data = mutableMapOf<String, Any>()
    private val notes = mutableListOf<String>()
    private var sealed: Boolean = false

    val isSealed: Boolean
        get() = sealed

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> put(key: String, value: T) {
        serializerOrNull(typeOf<T>()) ?: throw IllegalArgumentException("No serializer found for key '$key' and type '${typeOf<T>()}'.",)
        putResolved(key, value)
    }

    fun footnote(message: String) {
        if (sealed) return
        notes += message
    }

    suspend fun seal(success: Boolean = true, error: Throwable? = null) {
        if (sealed) return
        sealed = true

        context.write(
            SealedScrollEvent(
                scrollId = id,
                success = success,
                errorMessage = error?.message,
                data = data.toMap(),
                footnotes = notes.toList(),
                startedAtEpochMs = startedAtEpochMs,
                sealedAtEpochMs = nowEpochMs(),
            ),
        )
    }

    @PublishedApi
    internal fun putResolved(key: String, value: Any) {
        if (sealed) return
        data[key] = value
    }
}
