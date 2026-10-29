package com.ecommerce.catalogservice.exception

import feign.FeignException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(CatalogEntryNotFoundException::class)
    fun handleNotFound(ex: CatalogEntryNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Not found: {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                status = 404,
                error = "Not Found",
                message = ex.message ?: "Resource not found",
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )
    }

    /**
     * A write lost a race with another writer (the document's version moved,
     * or two first-time inserts collided on the product id). The state the
     * caller based its request on is stale: 409, re-read and retry.
     */
    @ExceptionHandler(OptimisticLockingFailureException::class, DuplicateKeyException::class)
    fun handleConcurrentModification(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Concurrent modification on {}: {}", request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                status = 409,
                error = "Conflict",
                message = "The catalog entry was modified concurrently; re-read it and retry",
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            FieldError(it.field, it.defaultMessage ?: "Invalid value")
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                status = 400,
                error = "Validation Failed",
                message = "Request validation failed",
                path = request.requestURI,
                correlationId = MDC.get("correlationId"),
                fieldErrors = fieldErrors
            )
        )
    }

    /**
     * Spring MVC's own client errors (bad path/query values, missing params, unreadable
     * bodies, failed method-parameter validation, wrong verb/media type...) keep the
     * status Spring assigns them; without this the catch-all below would report 500.
     */
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        ServletRequestBindingException::class,
        HttpMessageNotReadableException::class,
        HandlerMethodValidationException::class,
        HttpRequestMethodNotSupportedException::class,
        HttpMediaTypeNotSupportedException::class,
        HttpMediaTypeNotAcceptableException::class,
        NoResourceFoundException::class
    )
    fun handleClientError(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val status = (ex as? org.springframework.web.ErrorResponse)?.statusCode?.value() ?: 400
        val reason = HttpStatus.resolve(status)?.reasonPhrase ?: "Bad Request"
        return ResponseEntity.status(status).body(
            ErrorResponse(
                status = status,
                error = reason,
                message = if (ex is HandlerMethodValidationException) "Request validation failed" else (ex.message ?: reason),
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )
    }

    /** product-service answered 404: the product being synced does not exist upstream. */
    @ExceptionHandler(FeignException.NotFound::class)
    fun handleUpstreamNotFound(ex: FeignException.NotFound, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Upstream not found on {}: {}", request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                status = 404,
                error = "Not Found",
                message = "Product not found in product-service",
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                status = 500,
                error = "Internal Server Error",
                message = "An unexpected error occurred",
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )
    }
}
