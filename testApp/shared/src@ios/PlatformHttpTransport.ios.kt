package scribe.demo

actual suspend fun platformHttpGet(url: String): TransportResponse =
    TransportResponse(
        statusCode = 501,
        body = "Local OpenObserve checks are not implemented for iOS in this sample.",
    )

actual suspend fun platformHttpPostJson(
    url: String,
    username: String,
    password: String,
    body: String,
): TransportResponse =
    TransportResponse(
        statusCode = 501,
        body = "Local OpenObserve uploads are not implemented for iOS in this sample.",
    )
