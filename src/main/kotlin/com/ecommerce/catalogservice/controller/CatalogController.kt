package com.ecommerce.catalogservice.controller

import com.ecommerce.catalogservice.dto.CatalogEntryDTO
import com.ecommerce.catalogservice.dto.UpdateCatalogEntryRequest
import com.ecommerce.catalogservice.service.CatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog", description = "Product catalog browse and search endpoints")
class CatalogController(private val catalogService: CatalogService) {

    @GetMapping("/search")
    @Operation(
        summary = "Search catalog",
        description = "Relevance-ranked full-text search over product name, brand and description; " +
            "falls back to a case-insensitive name substring match for partial words."
    )
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<CatalogEntryDTO>> {
        return ResponseEntity.ok(catalogService.searchCatalog(q, PageRequest.of(page, size)))
    }

    @GetMapping
    @Operation(summary = "Browse all catalog entries")
    fun browse(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<CatalogEntryDTO>> {
        return ResponseEntity.ok(catalogService.getAllEntries(PageRequest.of(page, size)))
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "Get catalog entries by category")
    fun getByCategory(@PathVariable categoryId: Long): ResponseEntity<List<CatalogEntryDTO>> {
        return ResponseEntity.ok(catalogService.getCatalogByCategory(categoryId))
    }

    @GetMapping("/featured")
    @Operation(summary = "Get featured products")
    fun getFeatured(): ResponseEntity<List<CatalogEntryDTO>> {
        return ResponseEntity.ok(catalogService.getFeaturedProducts())
    }

    @GetMapping("/product/{productId}")
    @Operation(summary = "Get catalog entry by product ID")
    fun getByProductId(@PathVariable productId: Long): ResponseEntity<CatalogEntryDTO> {
        return ResponseEntity.ok(catalogService.getCatalogEntryByProductId(productId))
    }

    @PostMapping("/sync/{productId}")
    @Operation(summary = "Sync catalog entry from product service (Admin)", security = [SecurityRequirement(name = "bearerAuth")])
    fun sync(@PathVariable productId: Long): ResponseEntity<CatalogEntryDTO> {
        return ResponseEntity.ok(catalogService.syncFromProductService(productId))
    }

    @PatchMapping("/product/{productId}")
    @Operation(
        summary = "Update catalog entry metadata (Admin)",
        description = "Partial update of the catalog-owned fields. Answers 409 if the entry was modified concurrently.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    fun update(
        @PathVariable productId: Long,
        @RequestBody request: UpdateCatalogEntryRequest
    ): ResponseEntity<CatalogEntryDTO> {
        return ResponseEntity.ok(catalogService.updateCatalogEntry(productId, request))
    }
}
