package com.rafambn.scribe

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf

/**
 * Mutable event context that accumulates data until it is sealed into a [SealedScroll].
 */
class Scroll internal constructor(
    val id: String,
    imprint: Map<String, JsonElement> = emptyMap(),
    private val onSeal: (Scroll) -> Unit = {},
    private val onSealed: (Scroll) -> Unit = {},
    private val emitSealedScroll: suspend (SealedScroll) -> Unit = {},
    private val tryEmitSealedScroll: (SealedScroll) -> Unit = {},
) {
    private val sharedContext = imprint.toMap()
    private val _data = mutableMapOf<String, JsonElement>()
    private var sealed: Boolean = false

    /**
     * `true` after the scroll has been sealed.
     */
    val isSealed: Boolean
        get() = sealed

    /**
     * Seals this scroll and suspends until its [SealedScroll] is enqueued.
     */
    suspend fun seal(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return toSealedScroll(success, error)
        }
        onSeal(this)
        sealed = true
        onSealed(this)

        val result = toSealedScroll(success, error)
        emitSealedScroll(result)
        return result
    }

    /**
     * Seals this scroll using non-blocking best-effort enqueue.
     */
    fun looseSeal(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return toSealedScroll(success, error)
        }
        onSeal(this)
        sealed = true
        onSealed(this)

        val result = toSealedScroll(success, error)
        tryEmitSealedScroll(result)
        return result
    }

    /**
     * Writes a string value under [key].
     */
    fun writeString(key: String, value: String) {
        writeResolved(key, JsonPrimitive(value))
    }

    /**
     * Writes a numeric value under [key]. Non-finite floats are rejected.
     */
    fun writeNumber(key: String, value: Number) {
        ensureFinite(key, value)
        writeResolved(key, JsonPrimitive(value))
    }

    /**
     * Writes a boolean value under [key].
     */
    fun writeBoolean(key: String, value: Boolean) {
        writeResolved(key, JsonPrimitive(value))
    }

    /**
     * Serializes [value] with kotlinx.serialization and stores it under [key].
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> writeSerializable(key: String, value: T) {
        val serializer = serializerOrNull(typeOf<T>())
            ?: throw IllegalArgumentException("No serializer found for key '$key' and type '${typeOf<T>()}'.")
        writeResolved(key, Json.encodeToJsonElement(serializer, value))
    }

    /**
     * Reads from local data first, then from immutable shared context.
     */
    fun read(key: String): JsonElement? = _data[key] ?: sharedContext[key]

    /**
     * Removes a local value by [key]. Returns `null` when sealed or absent.
     */
    fun erase(key: String): JsonElement? {
        if (sealed) return null
        return _data.remove(key)
    }

    @PublishedApi
    internal fun writeResolved(key: String, value: JsonElement) {
        if (sealed) return
        _data[key] = value
    }

    private fun toSealedScroll(success: Boolean, error: Throwable?): SealedScroll =
        SealedScroll(
            scrollId = id,
            success = success,
            errorMessage = error?.message,
            context = sharedContext,
            data = _data.toMap(),
        )

    private fun ensureFinite(key: String, value: Number) {
        when (value) {
            is Double -> require(value.isFinite()) { "Non-finite numbers are not supported for key '$key'." }
            is Float -> require(value.isFinite()) { "Non-finite numbers are not supported for key '$key'." }
        }
    }
}
