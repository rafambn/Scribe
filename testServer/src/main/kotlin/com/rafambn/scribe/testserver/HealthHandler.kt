package com.rafambn.scribe.testserver

import com.rafambn.scribe.seal
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.charset.StandardCharsets

object HealthHandler : HttpHandler {
    private val logger = LoggerFactory.getLogger(HealthHandler::class.java)

    override fun handle(exchange: HttpExchange) {
        val scroll = TestServerScribe.newScroll()
        scroll["normal-scroll"] = JsonPrimitive("normal-scroll")
        scroll.seal(TestServerScribe)
        try {
            MDC.put("http.method", exchange.requestMethod)
            MDC.put("http.path", exchange.requestURI.path)
            logger.info("Health check requested")
            exchange.respond(statusCode = 200, body = "OK\n")
        } finally {
            MDC.clear()
        }
    }
}

private fun HttpExchange.respond(statusCode: Int, body: String) {
    val response = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    sendResponseHeaders(statusCode, response.size.toLong())
    responseBody.use { it.write(response) }
}
