package com.ecommerce.catalogservice.service

import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.dto.CategoryDTO
import com.ecommerce.catalogservice.dto.ProductDTO
import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import com.ecommerce.catalogservice.exception.CatalogEntryNotFoundException
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class CatalogServiceTest {

    @Mock
    private lateinit var catalogEntryRepository: CatalogEntryRepository

    @Mock
    private lateinit var productServiceClient: ProductServiceClient

    @InjectMocks
    private lateinit var catalogService: CatalogService

    private fun sampleEntry() = CatalogEntry(
        productId = 100L,
        productSku = "SKU-001",
        productName = "Test Product",
        description = "A test product",
        price = BigDecimal("29.99"),
        brand = "TestBrand",
        categoryId = 5L,
        categoryName = "Electronics",
        featured = false,
        stockStatus = StockStatus.AVAILABLE
    )

    @Test
    fun `getFeaturedProducts returns mapped DTOs`() {
        val entry = sampleEntry().also { it.featured = true }
        `when`(catalogEntryRepository.findByFeaturedTrue()).thenReturn(listOf(entry))

        val result = catalogService.getFeaturedProducts()

        assertEquals(1, result.size)
        assertEquals(100L, result[0].productId)
        assertTrue(result[0].featured)
    }

    @Test
    fun `getCatalogEntryByProductId throws when not found`() {
        `when`(catalogEntryRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows<CatalogEntryNotFoundException> {
            catalogService.getCatalogEntryByProductId(999L)
        }
    }

    @Test
    fun `getCatalogEntryByProductId returns DTO when found`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(entry))

        val result = catalogService.getCatalogEntryByProductId(100L)

        assertEquals(100L, result.productId)
        assertEquals(100L, result.id, "id mirrors productId now that the product id is the document id")
        assertEquals("SKU-001", result.productSku)
        assertEquals(StockStatus.AVAILABLE, result.stockStatus)
    }

    @Test
    fun `getCatalogByCategory returns entries for category`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findByCategoryId(5L)).thenReturn(listOf(entry))

        val result = catalogService.getCatalogByCategory(5L)

        assertEquals(1, result.size)
        assertEquals(5L, result[0].categoryId)
    }

    @Test
    fun `searchCatalog returns relevance-ranked text results when the text index has hits`() {
        val entry = sampleEntry()
        val pageable = PageRequest.of(0, 20)
        `when`(catalogEntryRepository.searchByText("Test", pageable)).thenReturn(PageImpl(listOf(entry)))

        val result = catalogService.searchCatalog("Test", pageable)

        assertEquals(1, result.totalElements)
        assertEquals("Test Product", result.content[0].productName)
        verify(catalogEntryRepository, never()).findByProductNameContainingIgnoreCase("Test", pageable)
    }

    @Test
    fun `searchCatalog falls back to a substring match when text search finds nothing`() {
        val entry = sampleEntry()
        val pageable = PageRequest.of(0, 20)
        `when`(catalogEntryRepository.searchByText("Tes", pageable)).thenReturn(PageImpl(emptyList()))
        `when`(catalogEntryRepository.findByProductNameContainingIgnoreCase("Tes", pageable))
            .thenReturn(PageImpl(listOf(entry)))

        val result = catalogService.searchCatalog("Tes", pageable)

        assertEquals(1, result.totalElements)
        assertEquals("Test Product", result.content[0].productName)
    }

    @Test
    fun `searchCatalog skips the text index for a blank query`() {
        val pageable = PageRequest.of(0, 20)
        `when`(catalogEntryRepository.findByProductNameContainingIgnoreCase("  ", pageable))
            .thenReturn(PageImpl(listOf(sampleEntry())))

        assertEquals(1, catalogService.searchCatalog("  ", pageable).totalElements)
        verify(catalogEntryRepository, never()).searchByText("  ", pageable)
    }

    @Test
    fun `updateCatalogEntry updates featured and price`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(entry))
        `when`(catalogEntryRepository.save(entry)).thenReturn(entry)

        val request = UpdateCatalogEntryRequest(featured = true, price = BigDecimal("49.99"))
        val result = catalogService.updateCatalogEntry(100L, request)

        assertTrue(result.featured)
        assertEquals(BigDecimal("49.99"), result.price)
    }

    @Test
    fun `updateCatalogEntry throws when entry not found`() {
        `when`(catalogEntryRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows<CatalogEntryNotFoundException> {
            catalogService.updateCatalogEntry(999L, UpdateCatalogEntryRequest(featured = true))
        }
    }

    @Test
    fun `syncFromProductService creates an entry keyed by the product id`() {
        `when`(productServiceClient.getProductById(100L)).thenReturn(
            ProductDTO(
                id = 100L, sku = "SKU-001", name = "Test Product", description = "A test product",
                price = BigDecimal("29.99"), stockQuantity = 3, brand = "TestBrand", categoryId = 5L, status = "ACTIVE"
            )
        )
        `when`(productServiceClient.getAllCategories()).thenReturn(
            listOf(CategoryDTO(id = 5L, name = "Electronics", description = null, parentCategoryId = null))
        )
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.empty())
        `when`(catalogEntryRepository.save(any(CatalogEntry::class.java))).thenAnswer { it.arguments[0] }

        val result = catalogService.syncFromProductService(100L)

        val saved = ArgumentCaptor.forClass(CatalogEntry::class.java)
        verify(catalogEntryRepository).save(saved.capture())
        assertEquals(100L, saved.value.productId)
        assertEquals("Electronics", saved.value.categoryName)
        assertEquals(StockStatus.LOW_STOCK, saved.value.stockStatus)
        assertEquals(100L, result.id)
    }

    @Test
    fun `prices are normalised to cents, as the former DECIMAL(19,2) column did`() {
        `when`(productServiceClient.getProductById(100L)).thenReturn(
            ProductDTO(
                id = 100L, sku = "SKU-001", name = "Test Product", description = null,
                price = BigDecimal("19.9900"), stockQuantity = 50, brand = null, categoryId = null, status = "ACTIVE"
            )
        )
        `when`(productServiceClient.getAllCategories()).thenReturn(emptyList())
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.empty())
        `when`(catalogEntryRepository.save(any(CatalogEntry::class.java))).thenAnswer { it.arguments[0] }

        assertEquals(BigDecimal("19.99"), catalogService.syncFromProductService(100L).price)

        val existing = sampleEntry()
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(existing))
        `when`(catalogEntryRepository.save(existing)).thenReturn(existing)

        val updated = catalogService.updateCatalogEntry(100L, UpdateCatalogEntryRequest(price = BigDecimal("10.005")))
        assertEquals(BigDecimal("10.01"), updated.price, "HALF_UP to two decimals")
    }

    @Test
    fun `syncFromProductService updates an existing entry in place and keeps merchandising fields`() {
        val existing = sampleEntry().also { it.featured = true; it.imageUrl = "http://img/1.png" }
        `when`(productServiceClient.getProductById(100L)).thenReturn(
            ProductDTO(
                id = 100L, sku = "SKU-001-v2", name = "Renamed Product", description = null,
                price = BigDecimal("39.99"), stockQuantity = 0, brand = null, categoryId = null, status = "ACTIVE"
            )
        )
        `when`(productServiceClient.getAllCategories()).thenReturn(emptyList())
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(existing))
        `when`(catalogEntryRepository.save(existing)).thenReturn(existing)

        val result = catalogService.syncFromProductService(100L)

        verify(catalogEntryRepository).save(existing) // the loaded document is updated in place, not replaced
        assertEquals("Renamed Product", result.productName)
        assertEquals(StockStatus.OUT_OF_STOCK, result.stockStatus)
        assertNull(result.categoryName)
        assertTrue(result.featured, "catalog-owned fields survive a sync")
        assertEquals("http://img/1.png", result.imageUrl)
    }

    @Test
    fun `syncFromProductService retries when it loses a first-insert race, then updates in place`() {
        `when`(productServiceClient.getProductById(100L)).thenReturn(
            ProductDTO(
                id = 100L, sku = "SKU-001", name = "Raced", description = null,
                price = BigDecimal("1.00"), stockQuantity = 50, brand = null, categoryId = null, status = "ACTIVE"
            )
        )
        `when`(productServiceClient.getAllCategories()).thenReturn(emptyList())
        val winner = sampleEntry().also { it.version = 0L }
        // 1st attempt: nothing there -> insert collides with a concurrent sync; 2nd: the winner's document is found
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.empty()).thenReturn(Optional.of(winner))
        `when`(catalogEntryRepository.save(any(CatalogEntry::class.java)))
            .thenThrow(DuplicateKeyException("E11000 duplicate key"))
            .thenAnswer { it.arguments[0] }

        val result = catalogService.syncFromProductService(100L)

        verify(catalogEntryRepository, times(2)).save(any(CatalogEntry::class.java))
        verify(productServiceClient, times(1)).getProductById(100L) // product fetched once, only the write is retried
        assertEquals("Raced", result.productName)
        assertEquals(0L, winner.version)
    }

    @Test
    fun `syncFromProductService gives up after five lost races and surfaces the conflict`() {
        `when`(productServiceClient.getProductById(100L)).thenReturn(
            ProductDTO(
                id = 100L, sku = "SKU-001", name = "X", description = null,
                price = BigDecimal("1.00"), stockQuantity = 50, brand = null, categoryId = null, status = "ACTIVE"
            )
        )
        `when`(productServiceClient.getAllCategories()).thenReturn(emptyList())
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(sampleEntry()))
        `when`(catalogEntryRepository.save(any(CatalogEntry::class.java)))
            .thenThrow(OptimisticLockingFailureException("version moved"))

        assertThrows<OptimisticLockingFailureException> { catalogService.syncFromProductService(100L) }
        verify(catalogEntryRepository, times(5)).save(any(CatalogEntry::class.java))
    }

    @Test
    fun `updateCatalogEntry does not retry a lost race - the conflict reaches the caller`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findById(100L)).thenReturn(Optional.of(entry))
        `when`(catalogEntryRepository.save(entry)).thenThrow(OptimisticLockingFailureException("version moved"))

        assertThrows<OptimisticLockingFailureException> {
            catalogService.updateCatalogEntry(100L, UpdateCatalogEntryRequest(featured = true))
        }
        verify(catalogEntryRepository, times(1)).save(entry)
    }
}
