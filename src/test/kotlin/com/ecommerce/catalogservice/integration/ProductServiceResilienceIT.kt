package com.ecommerce.catalogservice.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.catalogservice.config.CircuitBreakerTransitionLogger
import com.ecommerce.catalogservice.exception.GlobalExceptionHandler
import com.ecommerce.catalogservice.model.CatalogEntry
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider
import org.springframework.context.ApplicationContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Duration

/**
 * How the catalog sync reacts to a product-service that is slow, gone, erroring or flapping, through the real Feign
 * client, circuit breaker, time limiter and exception handler, against a real mongod.
 *
 * The circuit breaker is the one Spring Cloud builds for the Feign client; these tests prove the settings in
 * application.yaml reach it: the time limiter is above the Feign read timeout (the default 1 s used to cut every
 * slower call to the fallback), the breaker is called `product-service` and opens on its configured window, 4xx
 * answers do not count against it, and the HTTP client does not retry on its own (R-6).
 */
@TestPropertySource(
    properties = [
        "spring.cloud.openfeign.client.config.default.connect-timeout=1000",
        "spring.cloud.openfeign.client.config.default.read-timeout=3000",
        // the production value is 10s; shortened so the half-open recovery fits in a test
        "resilience4j.circuitbreaker.instances.product-service.wait-duration-in-open-state=2s",
        // the production value is 5s; shortened to fit under the 3 s read timeout used here
        "resilience4j.circuitbreaker.instances.product-service.slow-call-duration-threshold=2s"
    ]
)
class ProductServiceResilienceIT : AbstractCatalogIT() {

    @Autowired
    private lateinit var circuitBreakers: CircuitBreakerRegistry

    @Autowired
    private lateinit var context: ApplicationContext

    private val clients = Executors.newFixedThreadPool(30)

    private val handlerLog = ListAppender<ILoggingEvent>()
    private val breakerLog = ListAppender<ILoggingEvent>()
    private val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val breakerLogger = LoggerFactory.getLogger(CircuitBreakerTransitionLogger::class.java) as Logger

    @BeforeEach
    fun freshBreakerAndLogs() {
        circuitBreakers.circuitBreaker("product-service").reset() // every test starts with a closed, empty breaker
        handlerLog.start()
        breakerLog.start()
        handlerLogger.addAppender(handlerLog)
        breakerLogger.addAppender(breakerLog)
    }

    @AfterEach
    fun releaseLogs() {
        clients.shutdownNow()
        handlerLogger.detachAppender(handlerLog)
        breakerLogger.detachAppender(breakerLog)
        handlerLog.stop()
        breakerLog.stop()
        handlerLog.list.clear()
        breakerLog.list.clear()
    }

    private fun stubSlowProduct(id: Long, delayMillis: Int) {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/$id")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withFixedDelay(delayMillis).withBody(
                    """{"id":$id,"sku":"SKU-$id","name":"Slow $id","description":"d","price":19.99,"stockQuantity":25,
                       |"brand":"ACME","categoryId":5,"status":"ACTIVE"}""".trimMargin()
                )
            )
        )
    }

    private fun sync(id: Long) = call("POST", "/api/v1/catalog/sync/$id")

    // ───────────────────────── the configuration reaches the breaker Feign really uses ─────────────────────────

    /**
     * Spring Cloud CircuitBreaker's defaults hand every call to a worker thread behind a 1 s time limiter and a
     * thread-pool bulkhead of one thread per CPU with a 100-call queue (two concurrent calls in a 2-CPU container, the
     * rest rejected). All three are switched off in application.yaml: Feign's timeouts govern, the breaker only counts.
     */
    @Test
    fun `the breaker adds no worker thread, no time limiter and no bulkhead`() {
        val properties = context.getBean(Resilience4JConfigurationProperties::class.java)

        assertTrue(properties.isDisableThreadPool)
        assertTrue(properties.isDisableTimeLimiter)
        assertEquals(
            null, context.getBeanProvider(Resilience4jBulkheadProvider::class.java).getIfAvailable(),
            "spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false"
        )
    }

    @Test
    fun `many syncs run against product-service at the same time`() {
        val ids = (200L until 224L).toList()
        ids.forEach { stubSlowProduct(it, 1500) }
        stubCategories()

        val calls = ids.map { id -> CompletableFuture.supplyAsync({ sync(id).status }, clients) }

        calls.forEach { assertEquals(200, it.get(30, TimeUnit.SECONDS)) }
        assertTrue(maxOverlap() >= 16, "product-service calls in flight at once, with 24 clients: ${maxOverlap()}")
    }

    /** The highest number of product requests that product-service had in flight at the same time. */
    private fun maxOverlap(): Int {
        val spans = productService.allServeEvents
            .filter { it.request.url.startsWith("/api/v1/products/") && it.timing != null }
            .map { it.request.loggedDate.time to it.request.loggedDate.time + it.timing.totalTime }
        return spans.maxOfOrNull { a -> spans.count { b -> b.first <= a.first && a.first < b.second } } ?: 0
    }

    @Test
    fun `the Feign breaker is the one named product-service, with the configured window`() {
        stubProduct(60)
        stubCategories()
        assertEquals(200, sync(60).status)

        assertEquals(
            listOf("product-service"), circuitBreakers.allCircuitBreakers.map { it.name },
            "one breaker for the downstream, not one per client method"
        )
        val breaker = circuitBreakers.circuitBreaker("product-service")
        assertEquals(10, breaker.circuitBreakerConfig.slidingWindowSize)
        assertEquals(5, breaker.circuitBreakerConfig.minimumNumberOfCalls)
        assertEquals(80f, breaker.circuitBreakerConfig.slowCallRateThreshold)
        assertEquals(2, breaker.metrics.numberOfSuccessfulCalls, "the product call and the categories call went through it")
    }

    // ───────────────────────── slow ─────────────────────────

    @Test
    fun `a product-service that takes 2_5 s is waited for, not cut at the default 1 s time limit`() {
        stubSlowProduct(61, 2500)
        stubCategories()
        val started = System.nanoTime()

        val response = sync(61)

        assertEquals(200, response.status, response.body)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(2400))
        assertTrue(repository.existsById(61L))
    }

    /** Slow is a failure mode too: every call that takes the full read timeout holds a request thread. */
    @Test
    fun `a product-service that answers too slowly opens the breaker, and later calls fail fast`() {
        val ids = (300L until 305L).toList()
        ids.forEach { stubSlowProduct(it, 2500) } // over the 2 s slow-call threshold used here
        stubCategories()

        // five slow (but successful) answers fill the window, all of them over the threshold
        ids.map { id -> CompletableFuture.supplyAsync({ sync(id).status }, clients) }.forEach { assertEquals(200, it.get(30, TimeUnit.SECONDS)) }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakers.circuitBreaker("product-service").state)

        stubProduct(305)
        val started = System.nanoTime()
        val refused = sync(305)

        assertEquals(503, refused.status, refused.body)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1), "refused by the open breaker, not after another 2.5 s")
        productService.verify(0, getRequestedFor(urlEqualTo("/api/v1/products/305")))
    }

    @Test
    fun `a slow categories call is waited for too, so the category name is not lost`() {
        stubProduct(62, categoryId = 5)
        productService.stubFor(
            get(urlEqualTo("/api/v1/categories")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withFixedDelay(2000)
                    .withBody("""[{"id":5,"name":"Electronics","description":"Gadgets","parentCategoryId":null}]""")
            )
        )

        val response = sync(62)

        assertEquals(200, response.status, response.body)
        assertEquals("Electronics", response.json["categoryName"].asString())
    }

    @Test
    fun `a product-service slower than the read timeout is a 503 after the timeout, and is asked once`() {
        stubSlowProduct(63, 6000)
        val started = System.nanoTime()

        val response = sync(63)

        assertEquals(503, response.status, response.body)
        assertEquals("Service temporarily unavailable", response.json["message"].asString())
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed >= Duration.ofMillis(2800) && elapsed < Duration.ofSeconds(5), "took $elapsed")
        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/63")))
        assertFalse(repository.existsById(63L))
    }

    // ───────────────────────── gone, erroring, resetting ─────────────────────────

    @Test
    fun `a connection reset by product-service is a 503 and nobody retries it`() {
        // Not a connect failure, so FeignConfig.ConnectionFailureRetryer leaves it alone, and the HTTP client no
        // longer re-sends an idempotent GET on a reset by itself (R-6): exactly one request reaches the peer.
        productService.stubFor(get(urlEqualTo("/api/v1/products/64")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        assertEquals(503, sync(64).status)

        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/64")))
    }

    @Test
    fun `a 503 from product-service is a 503, sent once and not repeated after a second`() {
        // Apache HttpClient 5's default strategy used to retry a 503 once after 1 s underneath Feign.
        productService.stubFor(get(urlEqualTo("/api/v1/products/65")).willReturn(aResponse().withStatus(503)))
        val started = System.nanoTime()

        val response = sync(65)

        assertEquals(503, response.status)
        assertEquals("Service temporarily unavailable", response.json["message"].asString())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(900))
        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/65")))
    }

    @Test
    fun `a 500 from product-service is a 502 with a fixed message that leaks nothing`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/66")).willReturn(aResponse().withStatus(500).withBody("java.lang.NullPointerException at com.ecommerce"))
        )

        val response = sync(66)

        assertEquals(502, response.status, response.body)
        assertEquals("Upstream service error", response.json["message"].asString())
        assertFalse(response.body.contains("NullPointer"))
    }

    @Test
    fun `an unknown product is a 404, not an outage`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/67")).willReturn(
                aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                    .withBody("""{"status":404,"error":"Not Found","message":"Product not found with id: 67"}""")
            )
        )

        val response = sync(67)

        assertEquals(404, response.status, response.body)
        assertEquals("Product not found in product-service", response.json["message"].asString())
    }

    // ───────────────────────── what the sync does with what it gets ─────────────────────────

    @Test
    fun `a failing categories call keeps the category name an earlier sync stored`() {
        repository.save(
            CatalogEntry(
                productId = 68, productSku = "SKU-68", productName = "Before", price = BigDecimal("5.00"),
                categoryId = 5, categoryName = "Electronics"
            )
        )
        stubProduct(68, categoryId = 5)
        productService.stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse().withStatus(500)))

        val response = sync(68)

        assertEquals(200, response.status, response.body)
        assertEquals("Electronics", response.json["categoryName"].asString(), "a transient failure must not blank the name")
        assertEquals("Electronics", repository.findById(68L).get().categoryName)
    }

    @Test
    fun `a product with an unusable price is refused with a 502 and nothing is stored`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/69")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"id":69,"sku":"SKU-69","name":"Free","description":null,"price":-1.5,"stockQuantity":5,
                       |"brand":null,"categoryId":null,"status":"ACTIVE"}""".trimMargin()
                )
            )
        )

        val response = sync(69)

        assertEquals(502, response.status, response.body)
        assertFalse(repository.existsById(69L))
    }

    @Test
    fun `a product with a price whose exponent would blow up the rounding is refused quickly`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/70")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"id":70,"sku":"SKU-70","name":"Bomb","description":null,"price":1E+999999999,"stockQuantity":5,
                       |"brand":null,"categoryId":null,"status":"ACTIVE"}""".trimMargin()
                )
            )
        )
        val started = System.nanoTime()

        val response = sync(70)

        assertEquals(502, response.status, response.body)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        assertFalse(repository.existsById(70L))
    }

    // ───────────────────────── the breaker ─────────────────────────

    @Test
    fun `repeated outages open the breaker, which then rejects without calling the peer, and closes again on recovery`() {
        productService.stubFor(get(urlEqualTo("/api/v1/products/71")).willReturn(aResponse().withStatus(503)))

        repeat(5) { assertEquals(503, sync(71).status) }
        val breaker = circuitBreakers.circuitBreaker("product-service")
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        productService.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/71")))

        // open: rejected locally, instantly, without a request to product-service
        val started = System.nanoTime()
        repeat(20) { assertEquals(503, sync(71).status) }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3), "20 rejected calls")
        productService.verify(5, getRequestedFor(urlEqualTo("/api/v1/products/71")))

        // 25 failed requests: one line for the breaker, at most one for the handler, nothing with a stack trace
        assertEquals(listOf(Level.WARN), breakerLog.list.map { it.level })
        assertTrue(breakerLog.list[0].formattedMessage.contains("product-service") && breakerLog.list[0].formattedMessage.contains("OPEN"))
        assertTrue(handlerLog.list.size <= 1, "handler lines: ${handlerLog.list.map { it.formattedMessage }}")
        assertTrue(handlerLog.list.all { it.throwableProxy == null })

        // product-service comes back; after the open period the breaker lets calls through and closes again
        productService.resetAll()
        stubProduct(72)
        stubCategories()
        Thread.sleep(2300)
        repeat(3) { assertEquals(200, sync(72).status) } // permittedNumberOfCallsInHalfOpenState
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(listOf(Level.WARN, Level.INFO), breakerLog.list.map { it.level })
    }

    @Test
    fun `client errors from product-service never open the breaker`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/73")).willReturn(
                aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody("""{"status":404,"error":"Not Found","message":"x"}""")
            )
        )

        repeat(15) { assertEquals(404, sync(73).status) }

        val breaker = circuitBreakers.circuitBreaker("product-service")
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertEquals(0, breaker.metrics.numberOfFailedCalls, "a 404 is an answer, not a failure")
        productService.verify(15, getRequestedFor(urlEqualTo("/api/v1/products/73")))
        assertNull(breakerLog.list.firstOrNull())
    }
}
