package com.ecommerce.catalogservice.exception

/**
 * product-service gave no answer: connection refused or timed out, no instance registered, or the circuit
 * breaker is open (503, L4). Not a bug and not worth a stack trace.
 */
open class ProductServiceUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * product-service answered, but not with something usable: a 5xx other than 503/504, an unexpected 4xx, a body
 * that cannot be decoded, or product data that fails the sanity checks of the sync (502). [upstreamStatus] is
 * the HTTP status it sent, or -1 when there was none.
 */
class ProductServiceErrorException(message: String, val upstreamStatus: Int) : RuntimeException(message)
