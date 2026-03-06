package com.rafambn.scribe

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf

class Scroll(
    val id: String,
    val context: Scribe,
    contextData: Map<String, JsonElement> = emptyMap(),
) {
    private val _contextData = contextData.toMap()
    private val _data = mutableMapOf<String, JsonElement>()
    private var sealed: Boolean = false

    val isSealed: Boolean
        get() = sealed

    suspend fun seal(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return SealedScroll(
                scrollId = id,
                success = success,
                errorMessage = error?.message,
                context = _contextData,
                data = _data.toMap(),
            )
        }
        context.margins?.footer(this)
        sealed = true

        val result = SealedScroll(
            scrollId = id,
            success = success,
            errorMessage = error?.message,
            context = _contextData,
            data = _data.toMap(),
        )
        context.queue.send(result)
        return result
    }

    fun sealBlocking(success: Boolean = true, error: Throwable? = null): SealedScroll {
        if (sealed) {
            return SealedScroll(
                scrollId = id,
                success = success,
                errorMessage = error?.message,
                context = _contextData,
                data = _data.toMap(),
            )
        }
        context.margins?.footer(this)
        sealed = true

        val result = SealedScroll(
            scrollId = id,
            success = success,
            errorMessage = error?.message,
            context = _contextData,
            data = _data.toMap(),
        )
        context.queue.trySend(result)
        return result
    }

    fun putString(key: String, value: String) {
        putResolved(key, JsonPrimitive(value))
    }

    fun putNumber(key: String, value: Number) {
        ensureFinite(key, value)
        putResolved(key, JsonPrimitive(value))
    }

    fun putBoolean(key: String, value: Boolean) {
        putResolved(key, JsonPrimitive(value))
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> putSerializable(key: String, value: T) {
        val serializer = serializerOrNull(typeOf<T>())
            ?: throw IllegalArgumentException("No serializer found for key '$key' and type '${typeOf<T>()}'.")
        putResolved(key, Json.encodeToJsonElement(serializer, value))
    }

    fun get(key: String): JsonElement? = _data[key] ?: _contextData[key]

    fun remove(key: String): JsonElement? {
        if (sealed) return null
        return _data.remove(key)
    }

    @PublishedApi
    internal fun putResolved(key: String, value: JsonElement) {
        if (sealed) return
        _data[key] = value
    }

    private fun ensureFinite(key: String, value: Number) {
        when (value) {
            is Double -> require(value.isFinite()) { "Non-finite numbers are not supported for key '$key'." }
            is Float -> require(value.isFinite()) { "Non-finite numbers are not supported for key '$key'." }
        }
    }
}
