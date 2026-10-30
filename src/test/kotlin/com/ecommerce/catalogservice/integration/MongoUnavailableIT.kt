package com.ecommerce.catalogservice.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MongoDB is not there at all: the address nothing listens on. Before, the startup job threw and the container
 * crash-looped (a stack trace per restart, never ready); and with the driver's 30 s server selection every request
 * would then have hung for half a minute. Now the service starts, every request that needs the database fails in about
 * the server-selection timeout with a 503, and the log stays small however many requests fail.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "management.tracing.sampling.probability=0",
        // shorter than production (3 s / 2 s / 5 s) so the test is quick; the production values are asserted in MongoClientTimeoutsTest
        "catalog.mongo.server-selection-timeout=1s",
        "catalog.mongo.connect-timeout=500ms",
        "catalog.mongo.socket-timeout=1s",
        "spring.cloud.openfeign.client.config.product-service.url=http://localhost:1"
    ]
)
@ActiveProfiles("test")
@ContextConfiguration(initializers = [AbstractCatalogIT.ExcludeOtherTestConfigurations::class])
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class MongoUnavailableIT {

    companion object {
        /** Attached before the Spring context starts, so the startup job's own lines are counted too. */
        private val everything = ListAppender<ILoggingEvent>().apply {
            start()
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(this)
        }

        @DynamicPropertySource
        @JvmStatic
        fun nothingListensHere(registry: DynamicPropertyRegistry) {
            val port = ServerSocket(0).use { it.localPort } // free, and closed again
            registry.add("spring.mongodb.host") { "localhost" }
            registry.add("spring.mongodb.port") { port.toString() }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper.builder().build()

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `the service starts without a database and fails requests fast, with a fixed 503`() {
        // reaching this line means the context started: the startup job no longer throws
        val started = System.nanoTime()

        val response = get("/api/v1/catalog/product/1")

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(503, response.statusCode(), response.body())
        assertEquals("Service temporarily unavailable", json.readTree(response.body())["message"].asString())
        assertTrue(elapsed < Duration.ofSeconds(4), "took $elapsed (the driver default would be 30 s)")
    }

    @Test
    fun `every endpoint that needs the database is a 503, reads and writes alike`() {
        for (path in listOf("/api/v1/catalog", "/api/v1/catalog/search?q=mouse", "/api/v1/catalog/featured", "/api/v1/catalog/category/5")) {
            assertEquals(503, get(path).statusCode(), path)
        }
    }

    @Test
    fun `liveness stays up and readiness is not what restarts the pod`() {
        assertEquals(200, get("/actuator/health/liveness").statusCode(), "a restart cannot bring MongoDB back")
    }

    @Test
    fun `health tells the truth about the database`() {
        assertEquals(503, get("/actuator/health").statusCode())
    }

    @Test
    fun `a flood of requests against a dead database costs a handful of log lines, none with a stack trace`() {
        val clients = 40
        val pool = Executors.newFixedThreadPool(clients)
        val started = System.nanoTime()
        val statuses = (1..clients).map { pool.submit<Int> { get("/api/v1/catalog/product/$it").statusCode() } }.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(statuses.all { it == 503 }, "statuses $statuses")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10))
        val loud = everything.list.filter { it.level.isGreaterOrEqual(Level.WARN) }
        assertTrue(
            loud.size <= 4,
            "WARN/ERROR lines from startup through ${clients + 6} failed requests: " + loud.map { "${it.loggerName}: ${it.formattedMessage.take(120)}" }
        )
        val withStack = everything.list.filter { it.throwableProxy != null }
        assertTrue(
            withStack.isEmpty(),
            "lines with a stack trace: " + withStack.map { "${it.level} ${it.loggerName}: ${it.formattedMessage.take(120)}" }
        )
    }

    @Test
    fun `the driver's own per-operation chatter is off - it logged a 600 character INFO line for every request`() {
        repeat(10) { get("/api/v1/catalog/product/$it") }

        val driver = everything.list.filter { it.loggerName.startsWith("org.mongodb") }
        assertTrue(driver.isEmpty(), "driver lines: " + driver.map { it.formattedMessage.take(100) })
    }
}
