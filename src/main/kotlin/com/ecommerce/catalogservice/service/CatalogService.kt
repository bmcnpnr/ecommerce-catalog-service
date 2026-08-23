package com.ecommerce.catalogservice.service

import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.dto.CatalogEntryDTO
import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import com.ecommerce.catalogservice.exception.CatalogEntryNotFoundException
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ThreadLocalRandom

/**
 * Every write here touches exactly one document, and MongoDB guarantees
 * single-document atomicity, so there is no transaction boundary to declare.
 * Concurrency is handled by the document's `@Version`: the sync (idempotent,
 * server-driven) simply retries when it loses a race, a PATCH (client-driven)
 * surfaces the lost race as 409 so the caller can re-read and decide.
 */
@Service
class CatalogService(
    private val catalogEntryRepository: CatalogEntryRepository,
    private val productServiceClient: ProductServiceClient
) {

    private val log = LoggerFactory.getLogger(CatalogService::class.java)

    /**
     * Relevance-ranked full-text search over name, brand and description
     * (text index), falling back to a case-insensitive name substring match
     * when the text index has nothing — that covers partial words ("wirel"),
     * which `$text` tokenises away, and blank queries. The choice is made per
     * query string, so paging through one query never switches mode.
     */
    fun searchCatalog(query: String, pageable: Pageable): Page<CatalogEntryDTO> {
        if (query.isNotBlank()) {
            val ranked = catalogEntryRepository.searchByText(query, pageable)
            if (ranked.totalElements > 0) return ranked.map { it.toDTO() }
        }
        return catalogEntryRepository.findByProductNameContainingIgnoreCase(query, pageable)
            .map { it.toDTO() }
    }

    fun getCatalogByCategory(categoryId: Long): List<CatalogEntryDTO> {
        return catalogEntryRepository.findByCategoryId(categoryId).map { it.toDTO() }
    }

    fun getFeaturedProducts(): List<CatalogEntryDTO> {
        return catalogEntryRepository.findByFeaturedTrue().map { it.toDTO() }
    }

    fun getCatalogEntryByProductId(productId: Long): CatalogEntryDTO {
        return catalogEntryRepository.findById(productId)
            .orElseThrow { CatalogEntryNotFoundException("Catalog entry not found for product $productId") }
            .toDTO()
    }

    fun getAllEntries(pageable: Pageable): Page<CatalogEntryDTO> {
        return catalogEntryRepository.findAll(pageable).map { it.toDTO() }
    }

    fun syncFromProductService(productId: Long): CatalogEntryDTO {
        val product = productServiceClient.getProductById(productId)
        val categories = try { productServiceClient.getAllCategories() } catch (e: Exception) { emptyList() }
        val category = categories.find { it.id == product.categoryId }

        val stockStatus = when {
            product.stockQuantity <= 0 -> StockStatus.OUT_OF_STOCK
            product.stockQuantity < 10 -> StockStatus.LOW_STOCK
            else -> StockStatus.AVAILABLE
        }

        // Read-modify-write, retried if another writer got there first: the
        // product data was fetched once above, so a retry only re-reads the
        // document and re-applies the same projection — idempotent by design.
        val saved = retryingOnConcurrentWrite("sync of product $productId") {
            val existing = catalogEntryRepository.findById(productId)
            val entry = if (existing.isPresent) {
                val e = existing.get()
                e.productSku = product.sku
                e.productName = product.name
                e.description = product.description
                e.price = product.price.toCatalogPrice()
                e.brand = product.brand
                e.categoryId = product.categoryId
                e.categoryName = category?.name
                e.stockStatus = stockStatus
                e.updatedAt = CatalogEntry.now()
                e
            } else {
                CatalogEntry(
                    productId = productId,
                    productSku = product.sku,
                    productName = product.name,
                    description = product.description,
                    price = product.price.toCatalogPrice(),
                    brand = product.brand,
                    categoryId = product.categoryId,
                    categoryName = category?.name,
                    stockStatus = stockStatus
                )
            }
            catalogEntryRepository.save(entry)
        }

        log.info("Synced catalog entry for product {}", productId)
        return saved.toDTO()
    }

    /**
     * Partial update of the catalog-owned fields. Not retried: a PATCH carries
     * a client's intent based on what it last read, so if the document moved
     * underneath it the version check fails with OptimisticLockingFailureException
     * and the API answers 409 — the client re-reads and decides.
     */
    fun updateCatalogEntry(productId: Long, request: UpdateCatalogEntryRequest): CatalogEntryDTO {
        val entry = catalogEntryRepository.findById(productId)
            .orElseThrow { CatalogEntryNotFoundException("Catalog entry not found for product $productId") }

        request.featured?.let { entry.featured = it }
        request.imageUrl?.let { entry.imageUrl = it }
        request.price?.let { entry.price = it.toCatalogPrice() }
        entry.updatedAt = CatalogEntry.now()

        return catalogEntryRepository.save(entry).toDTO()
    }

    /**
     * Runs [block] up to [attempts] times while it loses a write race:
     * OptimisticLockingFailureException when the document's version moved, or
     * DuplicateKeyException when two first-time syncs both tried to insert the
     * same product id (only one insert can win on `_id`). Losers back off for
     * a few jittered milliseconds before retrying — immediate retries under a
     * burst of identical syncs tend to collide again. The last failure is
     * rethrown if every attempt loses, which the API maps to 409.
     */
    private fun <T> retryingOnConcurrentWrite(what: String, attempts: Int = RETRY_ATTEMPTS, block: () -> T): T {
        var lastFailure: RuntimeException? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: OptimisticLockingFailureException) {
                lastFailure = e
            } catch (e: DuplicateKeyException) {
                lastFailure = e
            }
            log.info("Concurrent write on {} (attempt {}/{}), retrying", what, attempt + 1, attempts)
            if (attempt < attempts - 1) {
                Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25) * (attempt + 1))
            }
        }
        throw lastFailure!!
    }

    /**
     * Catalog prices are cents. The relational column was DECIMAL(19,2) and
     * rounded on write; Decimal128 keeps whatever scale it is given (product-
     * service sends NUMERIC(19,4) values such as 19.9900), so the rounding is
     * applied here to keep the stored and served price contract unchanged.
     */
    private fun BigDecimal.toCatalogPrice(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private companion object {
        const val RETRY_ATTEMPTS = 5
    }

    private fun CatalogEntry.toDTO() = CatalogEntryDTO(
        id = productId,
        productId = productId,
        productSku = productSku,
        productName = productName,
        description = description,
        price = price,
        brand = brand,
        categoryId = categoryId,
        categoryName = categoryName,
        featured = featured,
        stockStatus = stockStatus,
        imageUrl = imageUrl,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
