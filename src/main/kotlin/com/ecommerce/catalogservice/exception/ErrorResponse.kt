package com.ecommerce.catalogservice.exception

import java.time.LocalDateTime

data class ErrorResponse(
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val correlationId: String? = null,
    val fieldErrors: List<FieldError> = emptyList()
)

data class FieldError(
    val field: String,
    val message: String
)
