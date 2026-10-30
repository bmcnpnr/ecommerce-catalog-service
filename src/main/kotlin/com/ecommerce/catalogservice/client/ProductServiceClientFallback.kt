package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.dto.CategoryDTO
import com.ecommerce.catalogservice.dto.ProductDTO
import com.ecommerce.catalogservice.exception.ProductServiceUnavailableException

/**
 * Used when product-service gave no answer (see [ProductServiceClientFallbackFactory]). A product cannot be
 * invented, so the sync is refused with a 503. The category list cannot be invented either: an empty list would
 * be indistinguishable from "there are no categories" and make the sync blank a stored category name, so this
 * fails as well and the sync decides what to do (it keeps the name it already has).
 *
 * Deliberately silent: it runs once per call during an outage, and the breaker's state transitions plus its
 * metrics already say what is going on.
 */
class ProductServiceClientFallback(
    /** What the call failed with; carried by the exception so the handler's single log line can say why. */
    private val cause: Throwable? = null
) : ProductServiceClient {

    override fun getProductById(id: Long): ProductDTO {
        throw ProductServiceUnavailableException("Product service unavailable", cause)
    }

    override fun getAllCategories(): List<CategoryDTO> {
        throw ProductServiceUnavailableException("Product service unavailable", cause)
    }
}
