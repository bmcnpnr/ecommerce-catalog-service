package com.ecommerce.catalogservice.config

import feign.Request
import feign.RetryableException
import org.apache.hc.client5.http.ConnectTimeoutException
import org.apache.hc.client5.http.HttpHostConnectException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ConnectionFailureRetryerTest {

    private fun retryable(cause: Throwable) = RetryableException(
        -1, "boom", Request.HttpMethod.GET, cause, null as Long?,
        Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1", emptyMap(), null, null, null)
    )

    @Test
    fun `connection refused and connect timeouts are retried, then propagated after the last attempt`() {
        val retryer = FeignConfig.ConnectionFailureRetryer(maxAttempts = 3, backoffMillis = 0)
        retryer.continueOrPropagate(retryable(HttpHostConnectException("refused")))   // attempt 1 -> 2
        retryer.continueOrPropagate(retryable(ConnectTimeoutException("connect")))   // attempt 2 -> 3
        val last = retryable(ConnectException("refused"))
        assertSame(last, assertThrows(RetryableException::class.java) { retryer.continueOrPropagate(last) })
    }

    @Test
    fun `a read timeout is never retried - the request may have been processed`() {
        val retryer = FeignConfig.ConnectionFailureRetryer(maxAttempts = 3, backoffMillis = 0)
        val readTimeout = retryable(SocketTimeoutException("Read timed out"))
        assertSame(readTimeout, assertThrows(RetryableException::class.java) { retryer.continueOrPropagate(readTimeout) })
    }

    @Test
    fun `clone starts a fresh attempt counter per call`() {
        val exhausted = FeignConfig.ConnectionFailureRetryer(maxAttempts = 2, backoffMillis = 0)
        exhausted.continueOrPropagate(retryable(ConnectException("refused")))
        assertThrows(RetryableException::class.java) { exhausted.continueOrPropagate(retryable(ConnectException("refused"))) }
        exhausted.clone().continueOrPropagate(retryable(ConnectException("refused"))) // fresh clone still has one retry
    }
}
