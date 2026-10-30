package com.ecommerce.catalogservice.exception

import com.ecommerce.catalogservice.logging.LogThrottle
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
import java.util.concurrent.TimeUnit

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    private companion object {
        // These lines can repeat at request rate (any anonymous client can ask for unknown ids; a dead database or
        // product-service under load; a bug on a hot path), so each distinct kind logs once per window and the
        // rest is only counted (L3, L4, L6).
        val ERROR_LOG = LogThrottle(10, TimeUnit.SECONDS)
        val UNAVAILABLE_LOG = LogThrottle(10, TimeUnit.SECONDS)
        val CLIENT_LOG = LogThrottle(10, TimeUnit.SECONDS)
    }

    @ExceptionHandler(CatalogEntryNotFoundException::class)
    fun handleNotFound(ex: CatalogEntryNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val suppressed = CLIENT_LOG.acquire("catalog.notFound")
        if (suppressed >= 0) log.warn("Not found: {}{}", sanitize(ex.message), LogThrottle.suppressedSuffix(suppressed))
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
        val suppressed = CLIENT_LOG.acquire("catalog.conflict")
        if (suppressed >= 0) log.warn("Concurrent modification on {}{}", sanitize(request.requestURI), LogThrottle.suppressedSuffix(suppressed))
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

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException, request: HttpServletRequest) =
        error(HttpStatus.UNAUTHORIZED, "Authentication required", request)

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException, request: HttpServletRequest) =
        error(HttpStatus.FORBIDDEN, "Access denied", request)

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(ex: InvalidRequestException, request: HttpServletRequest) =
        error(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid request", request)

    /**
     * Spring MVC's own client errors keep the status Spring assigns them, but the message is fixed per
     * error kind: nothing from the parser, the type system or the database reaches the caller.
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
        val message = when (ex) {
            is MethodArgumentTypeMismatchException -> "Invalid value for parameter '${safeName(ex.name)}'"
            is MissingServletRequestParameterException -> "Missing required parameter '${safeName(ex.parameterName)}'"
            is HandlerMethodValidationException -> "Request validation failed"
            is HttpMessageNotReadableException -> "Malformed request body"
            is HttpRequestMethodNotSupportedException -> "Method not allowed"
            is HttpMediaTypeNotSupportedException -> "Unsupported media type"
            is HttpMediaTypeNotAcceptableException -> "Not acceptable"
            is NoResourceFoundException -> "Resource not found"
            else -> "Invalid request"
        }
        val status = (ex as? org.springframework.web.ErrorResponse)?.statusCode?.value() ?: 400
        return error(HttpStatus.resolve(status) ?: HttpStatus.BAD_REQUEST, message, request)
    }

    private fun safeName(name: String?): String = (name ?: "").filter { it.isLetterOrDigit() || it in "_.-" }.take(64)

    private fun error(status: HttpStatus, message: String, request: HttpServletRequest) =
        ResponseEntity.status(status).body(
            ErrorResponse(
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = request.requestURI,
                correlationId = MDC.get("correlationId")
            )
        )

    /** product-service answered 404: the product being synced does not exist upstream. */
    @ExceptionHandler(FeignException.NotFound::class)
    fun handleUpstreamNotFound(ex: FeignException.NotFound, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val suppressed = CLIENT_LOG.acquire("catalog.upstreamNotFound")
        if (suppressed >= 0) log.warn("Upstream not found on {}{}", sanitize(request.requestURI), LogThrottle.suppressedSuffix(suppressed))
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

    /** product-service gave no answer (down, timed out, breaker open): an operating condition, not a bug. */
    @ExceptionHandler(ProductServiceUnavailableException::class)
    fun handleProductServiceUnavailable(ex: ProductServiceUnavailableException, request: HttpServletRequest) =
        unavailable(ex, request)

    /** product-service answered with something unusable (a 5xx, an undecodable body, implausible data). */
    @ExceptionHandler(ProductServiceErrorException::class)
    fun handleProductServiceError(ex: ProductServiceErrorException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val suppressed = UNAVAILABLE_LOG.acquire("ProductServiceErrorException:${ex.upstreamStatus}")
        if (suppressed >= 0) {
            log.warn(
                "product-service answered with status {} on {} {} (correlationId={}){}", ex.upstreamStatus,
                request.method, sanitize(request.requestURI), MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed)
            )
        }
        return error(HttpStatus.BAD_GATEWAY, "Upstream service error", request)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        DependencyFailures.unavailableCause(ex)?.let { return unavailable(it, request) }
        val suppressed = ERROR_LOG.acquire(LogThrottle.keyOf(ex))
        if (suppressed >= 0) {
            log.error(
                "Unhandled exception on {} {} (correlationId={}){}", request.method, sanitize(request.requestURI),
                MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed), ex
            )
        }
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

    /**
     * A dependency (MongoDB, product-service) did not answer: 503 with a fixed message and one throttled line
     * without a stack trace (L4).
     */
    private fun unavailable(cause: Throwable, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val suppressed = UNAVAILABLE_LOG.acquire(cause.javaClass.name)
        if (suppressed >= 0) {
            log.warn(
                "Dependency unavailable on {} {} (correlationId={}): {}{}", request.method, sanitize(request.requestURI),
                MDC.get("correlationId"), describe(cause), LogThrottle.suppressedSuffix(suppressed)
            )
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable", request)
    }

    /**
     * The failure and, when it differs, the deepest cause behind it ("Timed out while waiting for a server" says
     * little without the connection error behind it). The driver's own per-operation INFO lines are switched off, so
     * this line has to carry the reason.
     */
    private fun describe(cause: Throwable): String {
        var root: Throwable = cause
        var depth = 0
        while (root.cause != null && root.cause !== root && depth < 16) {
            root = root.cause!!
            depth++
        }
        return if (root === cause) brief(cause) else brief(cause) + "; root cause: " + brief(root)
    }

    /** Class and first line of the message, without control characters and capped, so nothing can forge a log line. */
    private fun brief(t: Throwable): String {
        val firstLine = (t.message ?: "").lineSequence().first().filter { !it.isISOControl() }.take(300)
        return t.javaClass.name + if (firstLine.isEmpty()) "" else ": $firstLine"
    }

    /** Strips control characters (CR/LF included) and caps the length, so user input cannot forge log lines. */
    private fun sanitize(value: String?): String = (value ?: "").filter { !it.isISOControl() }.take(256)
}
