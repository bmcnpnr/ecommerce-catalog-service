package com.ecommerce.catalogservice.client

import feign.FeignException
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

/**
 * A 404 from product-service is a real answer (the product does not exist), not an
 * outage: it is rethrown untouched so the exception handler can map it to 404 instead
 * of the generic "service unavailable" fallback swallowing it.
 */
@Component
class ProductServiceClientFallbackFactory : FallbackFactory<ProductServiceClient> {

    override fun create(cause: Throwable): ProductServiceClient =
        if (cause is FeignException.NotFound) {
            NotFoundPassThrough(cause)
        } else {
            ProductServiceClientFallback()
        }

    private class NotFoundPassThrough(private val cause: FeignException.NotFound) : ProductServiceClient {
        override fun getProductById(id: Long) = throw cause
        override fun getAllCategories(): List<com.ecommerce.catalogservice.dto.CategoryDTO> = throw cause
    }
}
