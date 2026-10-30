package com.ecommerce.catalogservice.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import feign.RetryableException
import feign.Retryer
import org.apache.hc.client5.http.ConnectTimeoutException
import java.net.ConnectException

@Configuration(proxyBeanMethods = false)
class FeignConfig {

    /**
     * Replaces Spring Cloud OpenFeign 5.0.1's `FeignHttpMessageConverters` bean
     * (declared `@ConditionalOnMissingBean` in FeignClientsConfiguration; the
     * per-client child contexts see this parent bean and skip their own).
     *
     * Why: the library initialises its converter list lazily and without
     * synchronisation — it publishes an empty ArrayList and only then fills
     * it. When the first Feign calls of a fresh JVM arrive concurrently (a
     * burst of catalog syncs right after a pod restart), a second thread sees
     * the empty list and SpringDecoder fails with
     * `DecodeException: 'messageConverters' must not be empty` — an HTTP 500
     * for that caller. The subclass below makes the first initialisation
     * mutually exclusive; once built, reads are lock-free.
     */
    @Bean
    fun feignHttpMessageConverters(
        customizers: ObjectProvider<ClientHttpMessageConvertersCustomizer>,
        cloudCustomizers: ObjectProvider<HttpMessageConverterCustomizer>
    ): FeignHttpMessageConverters = ThreadSafeFeignHttpMessageConverters(customizers, cloudCustomizers)

    /**
     * Retries only *connection* failures — the peer was never reached, so the
     * call is safe to repeat on any method, and the LoadBalancer picks an
     * instance afresh on every attempt. Read timeouts and HTTP errors are not
     * retried: the request may have been processed. This is what bridges the
     * few seconds between a peer pod vanishing and every registry cache
     * dropping it during a rollout.
     */
    @Bean
    fun feignRetryer(): Retryer = ConnectionFailureRetryer()

    /**
     * Apache HttpClient 5 quietly re-sends an idempotent request once on a reset connection and again, after a
     * pause, on a 503 (R-6). That is a second retry layer under [ConnectionFailureRetryer], one that contradicts
     * the rule above and doubles the load on a peer that is already struggling. Switched off, so the retryer is
     * the only one.
     */
    @Bean
    fun noAutomaticHttpClientRetries(): HttpClient5FeignConfiguration.HttpClientBuilderCustomizer =
        HttpClient5FeignConfiguration.HttpClientBuilderCustomizer { builder -> builder.disableAutomaticRetries() }

    /**
     * One circuit breaker per downstream service instead of one per client method (the default names are derived
     * from the interface and method, e.g. `ProductServiceClientgetProductByIdLong`). The name is what
     * `resilience4j.circuitbreaker.instances.product-service` and `resilience4j.timelimiter.instances.product-service`
     * in application.yaml configure; with the default names those blocks applied to nothing and the breaker ran on
     * resilience4j's defaults (100 calls before it could open, and a 1 s time limit that cut every slower call to
     * the fallback).
     */
    @Bean
    fun circuitBreakerNameResolver(): CircuitBreakerNameResolver =
        CircuitBreakerNameResolver { feignClientName, _, _ -> feignClientName }

    class ConnectionFailureRetryer(
        private val maxAttempts: Int = 3,
        private val backoffMillis: Long = 100
    ) : Retryer {
        private var attempt = 1

        override fun continueOrPropagate(e: RetryableException) {
            if (!isConnectionFailure(e.cause) || attempt >= maxAttempts) throw e
            Thread.sleep(backoffMillis * attempt)
            attempt++
        }

        override fun clone(): Retryer = ConnectionFailureRetryer(maxAttempts, backoffMillis)

        companion object {
            /** Connection refused (HttpHostConnectException is a ConnectException) or a connect-phase timeout; a read timeout is a plain SocketTimeoutException and is excluded on purpose. */
            fun isConnectionFailure(cause: Throwable?): Boolean =
                cause is ConnectException || cause is ConnectTimeoutException
        }
    }

    class ThreadSafeFeignHttpMessageConverters(
        customizers: ObjectProvider<ClientHttpMessageConvertersCustomizer>,
        cloudCustomizers: ObjectProvider<HttpMessageConverterCustomizer>
    ) : FeignHttpMessageConverters(customizers, cloudCustomizers) {

        @Volatile
        private var initialised = false

        override fun getConverters(): List<HttpMessageConverter<*>> {
            if (!initialised) {
                synchronized(this) {
                    if (!initialised) {
                        // The superclass builds the list on first access; holding
                        // the lock for that first access is the whole fix. The
                        // volatile write afterwards publishes the filled list.
                        super.getConverters()
                        initialised = true
                    }
                }
            }
            return super.getConverters()
        }
    }
}
