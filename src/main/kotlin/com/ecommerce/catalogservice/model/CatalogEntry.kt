package com.ecommerce.catalogservice.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "catalog_entries")
class CatalogEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "product_id", nullable = false, unique = true)
    var productId: Long,

    @Column(name = "product_sku", nullable = false)
    var productSku: String,

    @Column(name = "product_name", nullable = false)
    var productName: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(nullable = false, precision = 19, scale = 2)
    var price: BigDecimal,

    var brand: String? = null,

    @Column(name = "category_id")
    var categoryId: Long? = null,

    @Column(name = "category_name")
    var categoryName: String? = null,

    @Column(nullable = false)
    var featured: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false)
    var stockStatus: StockStatus = StockStatus.AVAILABLE,

    @Column(name = "image_url")
    var imageUrl: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
