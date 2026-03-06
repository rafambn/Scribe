package com.rafambn.scribe

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf

class Scroll internal constructor(
    val id: String,
    contextData: Map<String, JsonElement> = emptyMap(),
    private val onSeal: (Scroll) -> Unit = {},
    private val emitSealedScroll: suspend (SealedScroll) -> Unit = {},
    private val tryEmitSealedScroll: (SealedScroll) -> Unit = {},
) {
    private val sharedContext = contextData.toMap()
    private val _data = mutableMapOf<String, JsonElement>()
    private var sealed: Boolean = false

    val isSealed: Boolean
        get() = sealed

    suspend fun seal(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return toSealedScroll(success, error)
        }
        onSeal(this)
        sealed = true

        val result = toSealedScroll(success, error)
        emitSealedScroll(result)
        return result
    }

    fun trySeal(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return toSealedScroll(success, error)
        }
        onSeal(this)
        sealed = true

        val result = toSealedScroll(success, error)
        tryEmitSealedScroll(result)
        return result
    }

    fun writeString(key: String, value: String) {
        writeResolved(key, JsonPrimitive(value))
    }

    fun writeNumber(key: String, value: Number) {
        ensureFinite(key, value)
        writeResolved(key, JsonPrimitive(value))
    }

    fun writeBoolean(key: String, value: Boolean) {
        writeResolved(key, JsonPrimitive(value))
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> writeSerializable(key: String, value: T) {
        val serializer = serializerOrNull(typeOf<T>())
            ?: throw IllegalArgumentException("No serializer found for key '$key' and type '${typeOf<T>()}'.")
        writeResolved(key, Json.encodeToJsonElement(serializer, value))
    }

    fun read(key: String): JsonElement? = _data[key] ?: sharedContext[key]

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
