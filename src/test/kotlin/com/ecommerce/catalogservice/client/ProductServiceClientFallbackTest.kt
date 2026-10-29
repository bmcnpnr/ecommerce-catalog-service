package com.ecommerce.catalogservice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fallback's own contract. ProductServiceClientFallbackFactory routes every
 * product-service failure except a 404 here (`spring.cloud.openfeign.circuitbreaker.enabled`).
 * ProductServiceFailureIT pins what a caller actually sees when product-service fails.
 */
class ProductServiceClientFallbackTest {

    private val fallback = ProductServiceClientFallback()

    @Test
    fun `a product cannot be served from the fallback - the sync must fail rather than invent data`() {
        val ex = assertThrows<RuntimeException> { fallback.getProductById(1L) }

        assertEquals("Product service unavailable - circuit breaker open", ex.message)
    }

    @Test
    fun `categories degrade to an empty list - the sync then stores no category name`() {
        assertTrue(fallback.getAllCategories().isEmpty())
    }
}
