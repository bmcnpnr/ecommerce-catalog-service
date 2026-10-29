package com.ecommerce.catalogservice.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class UpdateCatalogEntryRequest(
    val featured: Boolean? = null,

    /** Absolute http(s) URL, at most 2048 characters; null leaves it alone, an empty string clears it. */
    @field:Size(max = 2048, message = "imageUrl must be at most 2048 characters")
    @field:Pattern(
        regexp = "^$|^(?i:https?)://[^\\s/?#]+\\S*$",
        message = "imageUrl must be an absolute http or https URL"
    )
    val imageUrl: String? = null,

    @field:DecimalMin(value = "0", inclusive = false, message = "price must be greater than 0")
    @field:Digits(integer = 15, fraction = 2, message = "price must have at most 15 integer digits and 2 decimals")
    val price: BigDecimal? = null
)
