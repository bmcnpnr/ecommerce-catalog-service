package com.ecommerce.catalogservice.repository

import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface CatalogEntryRepository : JpaRepository<CatalogEntry, Long> {
    fun findByCategoryId(categoryId: Long): List<CatalogEntry>
    fun findByFeaturedTrue(): List<CatalogEntry>
    fun findByStockStatus(stockStatus: StockStatus): List<CatalogEntry>
    fun findByProductNameContainingIgnoreCase(name: String, pageable: Pageable): Page<CatalogEntry>
    fun findByProductId(productId: Long): Optional<CatalogEntry>
    fun existsByProductId(productId: Long): Boolean
}
