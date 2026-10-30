package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.exception.ProductServiceUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fallback's own contract. ProductServiceClientFallbackFactory routes every product-service failure that means
 * "no answer" here (`spring.cloud.openfeign.circuitbreaker.enabled`). ProductServiceFailureIT pins what a caller
 * actually sees when product-service fails.
 */
class ProductServiceClientFallbackTest {

    private val fallback = ProductServiceClientFallback()

    @Test
    fun `a product cannot be served from the fallback - the sync must fail rather than invent data`() {
        val ex = assertThrows<ProductServiceUnavailableException> { fallback.getProductById(1L) }

        assertEquals("Product service unavailable", ex.message)
    }

    @Test
    fun `categories are not invented either - an empty list would make the sync blank a stored category name`() {
        assertThrows<ProductServiceUnavailableException> { fallback.getAllCategories() }
    }
}
