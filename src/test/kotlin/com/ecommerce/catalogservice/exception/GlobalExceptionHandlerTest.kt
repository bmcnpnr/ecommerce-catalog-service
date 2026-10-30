package com.ecommerce.catalogservice.exception

import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.core.MethodParameter
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.validation.FieldError as SpringFieldError

/**
 * Every handler answers with the shared [ErrorResponse] envelope: status and reason
 * phrase, a message safe to show a client, the request path and the request's
 * correlation id (taken from the MDC that CorrelationIdFilter fills).
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    private val request = MockHttpServletRequest("PATCH", "/api/v1/catalog/product/100")

    @BeforeEach
    fun putCorrelationId() = MDC.put("correlationId", "corr-123")

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `catalog entry not found is a 404 carrying the exception message`() {
        val response = handler.handleNotFound(CatalogEntryNotFoundException("Catalog entry not found for product 100"), request)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        val body = response.body!!
        assertEquals(404, body.status)
        assertEquals("Not Found", body.error)
        assertEquals("Catalog entry not found for product 100", body.message)
        assertEquals("/api/v1/catalog/product/100", body.path)
        assertEquals("corr-123", body.correlationId)
        assertTrue(body.fieldErrors.isEmpty())
    }

    @Test
    fun `a lost optimistic-lock race is a 409 with a retry hint, not the raw exception text`() {
        val response = handler.handleConcurrentModification(
            OptimisticLockingFailureException("Cannot save entity 100 with version 3; it was modified"), request
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = response.body!!
        assertEquals(409, body.status)
        assertEquals("Conflict", body.error)
        assertEquals("The catalog entry was modified concurrently; re-read it and retry", body.message)
        assertEquals("/api/v1/catalog/product/100", body.path)
        assertEquals("corr-123", body.correlationId)
    }

    @Test
    fun `a duplicate key on a first insert is the same 409`() {
        val response = handler.handleConcurrentModification(DuplicateKeyException("E11000 duplicate key"), request)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Conflict", response.body!!.error)
        assertFalse(response.body!!.message.contains("E11000"), "driver text does not leak to the client")
    }

    @Suppress("unused")
    private fun patchTarget(request: UpdateCatalogEntryRequest) = Unit

    @Test
    fun `validation failures are a 400 listing every rejected field`() {
        val bindingResult = BeanPropertyBindingResult(UpdateCatalogEntryRequest(), "request")
        bindingResult.addError(SpringFieldError("request", "price", "must be greater than 0"))
        bindingResult.addError(SpringFieldError("request", "imageUrl", "not-a-url", false, null, null, null))
        val parameter = MethodParameter(
            GlobalExceptionHandlerTest::class.java.getDeclaredMethod("patchTarget", UpdateCatalogEntryRequest::class.java), 0
        )

        val response = handler.handleValidation(MethodArgumentNotValidException(parameter, bindingResult), request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body!!
        assertEquals(400, body.status)
        assertEquals("Validation Failed", body.error)
        assertEquals("Request validation failed", body.message)
        assertEquals("corr-123", body.correlationId)
        assertEquals(
            listOf(FieldError("price", "must be greater than 0"), FieldError("imageUrl", "Invalid value")),
            body.fieldErrors,
            "a field error without a message gets a generic one"
        )
    }

    @Test
    fun `anything else is a 500 that hides the cause`() {
        val response = handler.handleGeneric(IllegalStateException("connection pool exhausted at 10.0.3.7"), request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        val body = response.body!!
        assertEquals(500, body.status)
        assertEquals("Internal Server Error", body.error)
        assertEquals("An unexpected error occurred", body.message)
        assertEquals("/api/v1/catalog/product/100", body.path)
        assertEquals("corr-123", body.correlationId)
    }

    @Test
    fun `without a correlation id in the MDC the field is null`() {
        MDC.clear()

        val response = handler.handleNotFound(CatalogEntryNotFoundException("gone"), request)

        assertNull(response.body!!.correlationId)
    }

    // ───────────────────────── dependency failures (L4) and log volume (L3, L6) ─────────────────────────

    private val logs = ListAppender<ILoggingEvent>()
    private val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger

    @BeforeEach
    fun captureHandlerLog() {
        logs.start()
        handlerLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseHandlerLog() {
        handlerLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `product-service giving no answer is a 503 with a fixed message`() {
        val response = handler.handleProductServiceUnavailable(ProductServiceUnavailableException("Product service unavailable"), request)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        val body = response.body!!
        assertEquals(503, body.status)
        assertEquals("Service Unavailable", body.error)
        assertEquals("Service temporarily unavailable", body.message)
        assertEquals("corr-123", body.correlationId)
    }

    @Test
    fun `product-service answering with something unusable is a 502 with a fixed message`() {
        val response = handler.handleProductServiceError(ProductServiceErrorException("product-service answered with status 500", 500), request)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("Upstream service error", response.body!!.message)
        assertFalse(response.body!!.message.contains("500"))
    }

    @Test
    fun `a database that does not answer is a 503 whichever way the driver or Spring Data report it`() {
        val failures = listOf(
            com.mongodb.MongoTimeoutException("Timed out while waiting for a server that matches ReadPreference primary"),
            org.springframework.dao.DataAccessResourceFailureException("catalogdb:27017: Connection refused"),
            org.springframework.dao.QueryTimeoutException("operation exceeded time limit"),
            org.springframework.data.mongodb.UncategorizedMongoDbException("wrapped", RuntimeException("x", com.mongodb.MongoTimeoutException("Timed out")))
        )

        failures.forEach { failure ->
            val response = handler.handleGeneric(failure, request)

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode, failure.toString())
            assertEquals("Service temporarily unavailable", response.body!!.message)
            assertFalse(response.body!!.message.contains("catalogdb"))
        }
    }

    @Test
    fun `a write conflict is still a 409, not a 503`() {
        val response = handler.handleConcurrentModification(DuplicateKeyException("E11000 duplicate key"), request)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    /** Only for the flood test below, so its throttle key (the exception class) is not shared with other tests. */
    private class FloodedMongo : org.springframework.dao.DataAccessResourceFailureException("catalogdb is down")

    @Test
    fun `a dead database under load logs one line without a stack trace, not one line per request`() {
        repeat(300) { assertEquals(HttpStatus.SERVICE_UNAVAILABLE, handler.handleGeneric(FloodedMongo(), request).statusCode) }

        assertEquals(1, logs.list.size, "300 failed requests, one log line")
        val line = logs.list.single()
        assertEquals(Level.WARN, line.level)
        assertNull(line.throwableProxy, "no stack trace for an unavailable dependency")
        assertTrue(line.formattedMessage.contains("Dependency unavailable"))
        assertTrue(line.formattedMessage.contains("FloodedMongo"))
    }

    /** Only for the flood test below. */
    private class FloodedProductService : ProductServiceUnavailableException("Product service unavailable")

    @Test
    fun `product-service down under load logs one line without a stack trace, not one line per request`() {
        repeat(300) { handler.handleProductServiceUnavailable(FloodedProductService(), request) }

        assertEquals(1, logs.list.size)
        assertEquals(Level.WARN, logs.list.single().level)
        assertNull(logs.list.single().throwableProxy)
    }

    @Test
    fun `a bug on a hot path logs one stack trace, not one per request`() {
        repeat(300) {
            val response = handler.handleGeneric(IllegalStateException("bug on the hot path"), request)
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        }

        assertEquals(1, logs.list.size)
        val line = logs.list.single()
        assertEquals(Level.ERROR, line.level)
        assertNotNull(line.throwableProxy, "the one logged occurrence keeps its stack trace")
        assertEquals("bug on the hot path", line.throwableProxy.message)
    }

    @Test
    fun `anonymous clients asking for unknown ids cannot flood the log`() {
        repeat(300) { handler.handleNotFound(CatalogEntryNotFoundException("Catalog entry not found for product $it"), request) }

        assertTrue(logs.list.size <= 1, "300 requests for ids that do not exist, at most one warning (it was one each)")
    }

    @Test
    fun `a storm of lost write races cannot flood the log either`() {
        repeat(300) { handler.handleConcurrentModification(OptimisticLockingFailureException("version moved"), request) }

        assertTrue(logs.list.size <= 1)
    }
}
