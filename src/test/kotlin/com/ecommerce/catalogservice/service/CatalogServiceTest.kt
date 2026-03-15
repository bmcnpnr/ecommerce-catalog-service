package com.ecommerce.catalogservice.service

import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import com.ecommerce.catalogservice.exception.CatalogEntryNotFoundException
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
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
        id = 1L,
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
        `when`(catalogEntryRepository.findByProductId(999L)).thenReturn(Optional.empty())

        assertThrows<CatalogEntryNotFoundException> {
            catalogService.getCatalogEntryByProductId(999L)
        }
    }

    @Test
    fun `getCatalogEntryByProductId returns DTO when found`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findByProductId(100L)).thenReturn(Optional.of(entry))

        val result = catalogService.getCatalogEntryByProductId(100L)

        assertEquals(100L, result.productId)
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
    fun `searchCatalog returns paged results`() {
        val entry = sampleEntry()
        val pageable = PageRequest.of(0, 20)
        `when`(catalogEntryRepository.findByProductNameContainingIgnoreCase("Test", pageable))
            .thenReturn(PageImpl(listOf(entry)))

        val result = catalogService.searchCatalog("Test", pageable)

        assertEquals(1, result.totalElements)
        assertEquals("Test Product", result.content[0].productName)
    }

    @Test
    fun `updateCatalogEntry updates featured and price`() {
        val entry = sampleEntry()
        `when`(catalogEntryRepository.findByProductId(100L)).thenReturn(Optional.of(entry))
        `when`(catalogEntryRepository.save(entry)).thenReturn(entry)

        val request = UpdateCatalogEntryRequest(featured = true, price = BigDecimal("49.99"))
        val result = catalogService.updateCatalogEntry(100L, request)

        assertTrue(result.featured)
        assertEquals(BigDecimal("49.99"), result.price)
    }

    @Test
    fun `updateCatalogEntry throws when entry not found`() {
        `when`(catalogEntryRepository.findByProductId(999L)).thenReturn(Optional.empty())

        assertThrows<CatalogEntryNotFoundException> {
            catalogService.updateCatalogEntry(999L, UpdateCatalogEntryRequest(featured = true))
        }
    }
}
