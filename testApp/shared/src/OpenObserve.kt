package scribe.demo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_ORG = "default"

data class OpenObserveConfig(
    val baseUrl: String = "http://localhost:5080",
    val organization: String = DEFAULT_ORG,
    val stream: String = "scribe_demo",
    val username: String = "root@example.com",
    val password: String = "Complexpass#123",
)

data class TransportResponse(
    val statusCode: Int,
    val body: String,
)

expect suspend fun platformHttpGet(url: String): TransportResponse

expect suspend fun platformHttpPostJson(
    url: String,
    username: String,
    password: String,
    body: String,
): TransportResponse

class OpenObserveClient(
    private val config: OpenObserveConfig,
    private val json: Json,
) {
    suspend fun ping(): Result<String> = runCatching {
        val response = platformHttpGet(config.baseUrl.trimEnd('/'))
        if (response.statusCode !in 200..399) {
            error("Reachability check failed with HTTP ${response.statusCode}: ${response.body}")
        }
        val message = if (response.statusCode in 300..399) {
            "Reachable at ${config.baseUrl} (HTTP ${response.statusCode}, redirected to /web/)."
        } else {
            "Reachable at ${config.baseUrl} (HTTP ${response.statusCode})."
        }
        message
    }

    suspend fun upload(record: OpenObservePayload): Result<String> = runCatching {
        val jsonRecord = JsonObject(record)
        val response = platformHttpPostJson(
            url = "${config.baseUrl.trimEnd('/')}/api/${config.organization}/${config.stream}/_json",
            username = config.username,
            password = config.password,
            body = json.encodeToString(ListSerializer(JsonObject.serializer()), listOf(jsonRecord)),
        )
        if (response.statusCode !in 200..299) {
            error("Upload failed with HTTP ${response.statusCode}: ${response.body}")
        }
        "Uploaded to ${config.stream} with HTTP ${response.statusCode}."
    }

    fun prettyRecord(record: OpenObservePayload): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(record))
}
