package com.ecommerce.catalogservice.repository

import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

/**
 * The document id is the product id, so "find by product" is [findById] and
 * "does an entry exist for this product" is [existsById] — both inherited.
 */
@Repository
interface CatalogEntryRepository : MongoRepository<CatalogEntry, Long>, CatalogEntryRepositoryCustom {
    fun findByCategoryId(categoryId: Long): List<CatalogEntry>
    fun findByFeaturedTrue(): List<CatalogEntry>
    fun findByStockStatus(stockStatus: StockStatus): List<CatalogEntry>

    /**
     * Case-insensitive substring match, translated by Spring Data into a
     * `{ productName: { $regex: <quoted query>, $options: "i" } }` filter. Like
     * the `ILIKE '%q%'` it replaces this cannot use an ordinary index; it is a
     * collection scan and is fine at catalog sizes. It is the fallback behind
     * [searchByText] for partial-word queries the text index cannot serve.
     */
    fun findByProductNameContainingIgnoreCase(name: String, pageable: Pageable): Page<CatalogEntry>
}
