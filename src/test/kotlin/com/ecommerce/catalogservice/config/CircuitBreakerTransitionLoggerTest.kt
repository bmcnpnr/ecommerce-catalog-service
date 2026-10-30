package com.ecommerce.catalogservice.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

class CircuitBreakerTransitionLoggerTest {

    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(CircuitBreakerTransitionLogger::class.java) as Logger

    @BeforeEach
    fun capture() {
        logger.level = Level.INFO
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(logs)
        logs.stop()
        logger.level = null
    }

    private fun registry() = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofMinutes(1)).build()
    )

    @Test
    fun `opening is one warn, closing is one info`() {
        val registry = registry()
        CircuitBreakerTransitionLogger(registry)
        val breaker = registry.circuitBreaker("product-service")

        repeat(4) { breaker.onError(0, TimeUnit.MILLISECONDS, RuntimeException("down")) }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        breaker.transitionToHalfOpenState()
        breaker.transitionToClosedState()

        assertEquals(listOf(Level.WARN, Level.INFO), logs.list.map { it.level })
        assertTrue(logs.list[0].formattedMessage.contains("product-service") && logs.list[0].formattedMessage.contains("OPEN"))
        assertTrue(logs.list[1].formattedMessage.contains("closed again"))
        assertTrue(logs.list.all { it.throwableProxy == null })
    }

    @Test
    fun `calls rejected while open log nothing`() {
        val registry = registry()
        CircuitBreakerTransitionLogger(registry)
        val breaker = registry.circuitBreaker("product-service")
        breaker.transitionToOpenState()
        logs.list.clear()

        repeat(1000) { assertEquals(false, breaker.tryAcquirePermission()) }

        assertTrue(logs.list.isEmpty(), "1000 rejected calls, no log lines")
    }

    @Test
    fun `forced open is a warn`() {
        val registry = registry()
        CircuitBreakerTransitionLogger(registry)

        registry.circuitBreaker("product-service").transitionToForcedOpenState()

        assertEquals(listOf(Level.WARN), logs.list.map { it.level })
    }

    @Test
    fun `breakers that already exist are covered, not only later ones`() {
        val registry = registry()
        val early = registry.circuitBreaker("early")

        CircuitBreakerTransitionLogger(registry)
        early.transitionToOpenState()

        assertEquals(1, logs.list.size)
        assertTrue(logs.list[0].formattedMessage.contains("early"))
    }
}
