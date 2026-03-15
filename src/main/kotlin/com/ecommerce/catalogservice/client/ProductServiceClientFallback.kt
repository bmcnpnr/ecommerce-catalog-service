package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.dto.CategoryDTO
import com.ecommerce.catalogservice.dto.ProductDTO
import org.springframework.stereotype.Component

@Component
class ProductServiceClientFallback : ProductServiceClient {

    override fun getProductById(id: Long): ProductDTO {
        throw RuntimeException("Product service unavailable - circuit breaker open")
    }

    override fun getAllCategories(): List<CategoryDTO> {
        return emptyList()
    }
}
