package com.ecommerce.catalogservice.dto

import java.math.BigDecimal

data class ProductDTO(
    val id: Long,
    val sku: String,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val stockQuantity: Int,
    val brand: String?,
    val categoryId: Long?,
    val status: String
)
