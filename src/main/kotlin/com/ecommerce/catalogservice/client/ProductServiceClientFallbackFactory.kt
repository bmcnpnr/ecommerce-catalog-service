package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.dto.CategoryDTO
import com.ecommerce.catalogservice.dto.ProductDTO
import com.ecommerce.catalogservice.exception.ProductServiceErrorException
import feign.FeignException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Turns every product-service failure into the exception that tells the caller what it means:
 *  - 404: a real answer (the product does not exist), rethrown untouched so the exception handler answers 404,
 *    and not counted by the breaker;
 *  - no answer (connection refused or timed out, no instance, 503/504, breaker open, time limit): 503;
 *  - an answer that is unusable (other 5xx, unexpected 4xx, undecodable body): 502;
 *  - anything else is a bug here and is rethrown as it is.
 *
 * Logs nothing: this runs once per call during an outage (L5).
 */
@Component
class ProductServiceClientFallbackFactory : FallbackFactory<ProductServiceClient> {

    override fun create(thrown: Throwable): ProductServiceClient {
        val cause = unwrap(thrown)
        return when {
            cause is FeignException.NotFound -> Failing(cause)
            isNoAnswer(cause) -> ProductServiceClientFallback(cause)
            cause is FeignException ->
                Failing(ProductServiceErrorException("product-service answered with status ${cause.status()}", cause.status()))
            cause is RuntimeException -> Failing(cause)
            else -> Failing(IllegalStateException(cause))
        }
    }

    private class Failing(private val failure: RuntimeException) : ProductServiceClient {
        override fun getProductById(id: Long): ProductDTO = throw failure
        override fun getAllCategories(): List<CategoryDTO> = throw failure
    }

    companion object {
        /**
         * Strips the wrappers a call picks up when it runs on another thread or through reflection, so that the checks
         * above see the Feign exception itself. With the breaker's thread pool off (application.yaml) there are none, but
         * a setting flipped back must not turn every 404 into a 503.
         */
        internal fun unwrap(thrown: Throwable): Throwable {
            var t = thrown
            var depth = 0
            while (depth < 8 && t.cause != null && t.cause !== t &&
                (t is ExecutionException || t is CompletionException || t is UndeclaredThrowableException || t is InvocationTargetException)
            ) {
                t = t.cause!!
                depth++
            }
            return t
        }

        /** True when the cause chain says product-service never produced a response we could use as an answer. */
        internal fun isNoAnswer(cause: Throwable): Boolean {
            var t: Throwable? = cause
            var depth = 0
            while (t != null && depth < 16) {
                if (t is CallNotPermittedException || t is TimeoutException || t is IOException) return true
                // -1: Feign's own status for "no response" (RetryableException); 503: the load balancer's
                // "no instance" answer or an overloaded peer; 504: a proxy's timeout.
                if (t is FeignException && (t.status() == -1 || t.status() == 503 || t.status() == 504)) return true
                t = if (t.cause === t) null else t.cause
                depth++
            }
            return false
        }
    }
}
