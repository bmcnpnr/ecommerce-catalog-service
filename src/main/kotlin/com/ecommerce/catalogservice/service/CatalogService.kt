package com.ecommerce.catalogservice.service

import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.dto.CatalogEntryDTO
import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import com.ecommerce.catalogservice.exception.CatalogEntryNotFoundException
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CatalogService(
    private val catalogEntryRepository: CatalogEntryRepository,
    private val productServiceClient: ProductServiceClient
) {

    private val log = LoggerFactory.getLogger(CatalogService::class.java)

    fun searchCatalog(query: String, pageable: Pageable): Page<CatalogEntryDTO> {
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
        return catalogEntryRepository.findByProductId(productId)
            .orElseThrow { CatalogEntryNotFoundException("Catalog entry not found for product $productId") }
            .toDTO()
    }

    fun getAllEntries(pageable: Pageable): Page<CatalogEntryDTO> {
        return catalogEntryRepository.findAll(pageable).map { it.toDTO() }
    }

    @Transactional
    fun syncFromProductService(productId: Long): CatalogEntryDTO {
        val product = productServiceClient.getProductById(productId)
        val categories = try { productServiceClient.getAllCategories() } catch (e: Exception) { emptyList() }
        val category = categories.find { it.id == product.categoryId }

        val stockStatus = when {
            product.stockQuantity <= 0 -> StockStatus.OUT_OF_STOCK
            product.stockQuantity < 10 -> StockStatus.LOW_STOCK
            else -> StockStatus.AVAILABLE
        }

        val existing = catalogEntryRepository.findByProductId(productId)
        val entry = if (existing.isPresent) {
            val e = existing.get()
            e.productSku = product.sku
            e.productName = product.name
            e.description = product.description
            e.price = product.price
            e.brand = product.brand
            e.categoryId = product.categoryId
            e.categoryName = category?.name
            e.stockStatus = stockStatus
            e.updatedAt = LocalDateTime.now()
            e
        } else {
            CatalogEntry(
                productId = productId,
                productSku = product.sku,
                productName = product.name,
                description = product.description,
                price = product.price,
                brand = product.brand,
                categoryId = product.categoryId,
                categoryName = category?.name,
                stockStatus = stockStatus
            )
        }

        log.info("Synced catalog entry for product {}", productId)
        return catalogEntryRepository.save(entry).toDTO()
    }

    @Transactional
    fun updateCatalogEntry(productId: Long, request: UpdateCatalogEntryRequest): CatalogEntryDTO {
        val entry = catalogEntryRepository.findByProductId(productId)
            .orElseThrow { CatalogEntryNotFoundException("Catalog entry not found for product $productId") }

        request.featured?.let { entry.featured = it }
        request.imageUrl?.let { entry.imageUrl = it }
        request.price?.let { entry.price = it }
        entry.updatedAt = LocalDateTime.now()

        return catalogEntryRepository.save(entry).toDTO()
    }

    private fun CatalogEntry.toDTO() = CatalogEntryDTO(
        id = id,
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
