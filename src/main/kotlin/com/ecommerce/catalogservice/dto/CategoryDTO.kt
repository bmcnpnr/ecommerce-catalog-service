package com.ecommerce.catalogservice.dto

data class CategoryDTO(
    val id: Long,
    val name: String,
    val description: String?,
    val parentCategoryId: Long?
)
