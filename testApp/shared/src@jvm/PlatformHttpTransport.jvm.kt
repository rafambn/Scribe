package scribe.demo

import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun platformHttpGet(url: String): TransportResponse = withContext(Dispatchers.Default) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5_000
        readTimeout = 5_000
    }
    try {
        val body = readResponse(connection)
        TransportResponse(connection.responseCode, body)
    } finally {
        connection.disconnect()
    }
}

actual suspend fun platformHttpPostJson(
    url: String,
    username: String,
    password: String,
    body: String,
): TransportResponse = withContext(Dispatchers.Default) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5_000
        readTimeout = 5_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", basicAuth(username, password))
    }
    try {
        connection.outputStream.use { output ->
            output.write(body.encodeToByteArray())
        }
        TransportResponse(connection.responseCode, readResponse(connection))
    } finally {
        connection.disconnect()
    }
}

private fun basicAuth(username: String, password: String): String {
    val credentials = Base64.getEncoder().encodeToString("$username:$password".encodeToByteArray())
    return "Basic $credentials"
}

private fun readResponse(connection: HttpURLConnection): String {
    val stream = connection.errorStream ?: connection.inputStream ?: return ""
    return stream.bufferedReader().use { it.readText() }
}
