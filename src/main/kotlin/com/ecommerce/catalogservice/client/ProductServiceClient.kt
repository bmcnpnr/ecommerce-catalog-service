package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.dto.CategoryDTO
import com.ecommerce.catalogservice.dto.ProductDTO
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "product-service", fallback = ProductServiceClientFallback::class)
interface ProductServiceClient {

    @GetMapping("/api/v1/products/{id}")
    fun getProductById(@PathVariable id: Long): ProductDTO

    @GetMapping("/api/v1/categories")
    fun getAllCategories(): List<CategoryDTO>
}
