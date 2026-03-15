package com.ecommerce.catalogservice.dto

import java.math.BigDecimal

data class UpdateCatalogEntryRequest(
    val featured: Boolean? = null,
    val imageUrl: String? = null,
    val price: BigDecimal? = null
)
