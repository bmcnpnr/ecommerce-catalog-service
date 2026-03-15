package com.ecommerce.catalogservice.dto

import com.ecommerce.catalogservice.model.StockStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class CatalogEntryDTO(
    val id: Long,
    val productId: Long,
    val productSku: String,
    val productName: String,
    val description: String?,
    val price: BigDecimal,
    val brand: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val featured: Boolean,
    val stockStatus: StockStatus,
    val imageUrl: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
