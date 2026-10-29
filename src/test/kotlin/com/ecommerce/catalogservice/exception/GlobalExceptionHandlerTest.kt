package com.ecommerce.catalogservice.exception

import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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
}
