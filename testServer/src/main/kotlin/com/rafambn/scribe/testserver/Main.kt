package com.rafambn.scribe.testserver

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

private const val SERVER_PORT = 8080
private val logger = LoggerFactory.getLogger("TestServer")

fun main() {
    TestServerScribe.hire()

    val server = HttpServer.create(InetSocketAddress(SERVER_PORT), 0).apply {
        createContext("/health", HealthHandler)
        executor = null
        start()
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(0)
            runBlocking { TestServerScribe.retire() }
        },
    )

    logger.info("Test server started on http://localhost:{}/health", SERVER_PORT)
}
