package com.ecommerce.catalogservice.client

import com.ecommerce.catalogservice.exception.ProductServiceErrorException
import com.ecommerce.catalogservice.exception.ProductServiceUnavailableException
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import feign.Response
import feign.RetryableException
import feign.codec.DecodeException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

/**
 * What each kind of product-service failure becomes once the circuit breaker hands it to the fallback: the
 * difference between "no such product" (404), "no answer" (503), "unusable answer" (502) and "our bug" (500).
 */
class ProductServiceClientFallbackFactoryTest {

    private val factory = ProductServiceClientFallbackFactory()

    private val request = Request.create(
        Request.HttpMethod.GET, "http://product-service/api/v1/products/7", emptyMap(), null, StandardCharsets.UTF_8, RequestTemplate()
    )

    private fun answered(status: Int): FeignException = FeignException.errorStatus(
        "ProductServiceClient#getProductById(Long)",
        Response.builder().status(status).reason("x").request(request).headers(emptyMap()).build()
    )

    private fun productFailureFor(cause: Throwable): Throwable = assertThrows<Throwable> { factory.create(cause).getProductById(7L) }

    private fun categoriesFailureFor(cause: Throwable): Throwable = assertThrows<Throwable> { factory.create(cause).getAllCategories() }

    @Test
    fun `a not found is an answer - it is rethrown untouched for the handler to turn into a 404`() {
        val notFound = answered(404)

        assertSame(notFound, productFailureFor(notFound))
        assertSame(notFound, categoriesFailureFor(notFound))
    }

    @Test
    fun `no answer is an outage`() {
        val refused = RetryableException(-1, "refused", Request.HttpMethod.GET, ConnectException("Connection refused"), null as Long?, request)
        val readTimeout = RetryableException(-1, "read timed out", Request.HttpMethod.GET, SocketTimeoutException("Read timed out"), null as Long?, request)
        val open = CircuitBreaker.ofDefaults("product-service")
        val causes = listOf(
            refused,
            readTimeout,
            TimeoutException("did not complete within 20000ms"),
            CallNotPermittedException.createCallNotPermittedException(open),
            answered(503), // the load balancer's "no instance", or an overloaded peer
            answered(504),
            DecodeException(200, "reset while reading the body", request, IOException("Connection reset"))
        )

        causes.forEach { cause ->
            assertEquals(ProductServiceUnavailableException::class.java, productFailureFor(cause).javaClass, "product: $cause")
            assertEquals(ProductServiceUnavailableException::class.java, categoriesFailureFor(cause).javaClass, "categories: $cause")
        }
    }

    @Test
    fun `an unusable answer is a bad gateway that carries only the status`() {
        val failure = productFailureFor(answered(500)) as ProductServiceErrorException

        assertEquals("product-service answered with status 500", failure.message)
        assertEquals(500, failure.upstreamStatus)
        assertEquals(502, (productFailureFor(answered(502)) as ProductServiceErrorException).upstreamStatus)
        assertEquals(400, (productFailureFor(answered(400)) as ProductServiceErrorException).upstreamStatus, "our request was rejected")
        assertEquals(ProductServiceErrorException::class.java, productFailureFor(DecodeException(200, "not json", request, IllegalStateException("bad token"))).javaClass)
    }

    /**
     * If the breaker ever runs the call on another thread again, the fallback gets the Feign exception wrapped in an
     * ExecutionException; a 404 must still be a 404 and an outage still an outage.
     */
    @Test
    fun `wrappers from another thread do not hide the real cause`() {
        val notFound = answered(404)

        assertSame(notFound, productFailureFor(java.util.concurrent.ExecutionException(notFound)))
        assertEquals(
            ProductServiceUnavailableException::class.java,
            productFailureFor(java.util.concurrent.CompletionException(java.util.concurrent.ExecutionException(answered(503)))).javaClass
        )
        assertEquals(ProductServiceErrorException::class.java, productFailureFor(java.util.concurrent.ExecutionException(answered(500))).javaClass)
    }

    @Test
    fun `an outage carries its cause so the single log line can say why`() {
        val refused = RetryableException(-1, "refused", Request.HttpMethod.GET, ConnectException("Connection refused"), null as Long?, request)

        assertSame(refused, productFailureFor(refused).cause)
        assertSame(refused, categoriesFailureFor(refused).cause)
    }

    @Test
    fun `anything else is a bug here and is not disguised`() {
        val bug = IllegalStateException("bug")

        assertSame(bug, productFailureFor(bug))
        val checked = productFailureFor(Exception("checked"))
        assertEquals(IllegalStateException::class.java, checked.javaClass)
        assertEquals("checked", checked.cause?.message)
    }
}
