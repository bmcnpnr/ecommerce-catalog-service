package com.ecommerce.catalogservice.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Breakers log transitions, not calls (L5): WARN when one opens, INFO when it closes again. While a breaker is open
 * every call is rejected without a network attempt, so a line per call would only be noise; the
 * `resilience4j_circuitbreaker_*` metrics and the CircuitBreakerOpen alert do the per-call accounting.
 *
 * Registered on the registry, so it covers the breakers that exist now and any created later (Spring Cloud
 * creates the Feign breaker lazily on the first call).
 */
@Component
class CircuitBreakerTransitionLogger(registry: CircuitBreakerRegistry) {

    private val log = LoggerFactory.getLogger(CircuitBreakerTransitionLogger::class.java)

    init {
        registry.allCircuitBreakers.forEach { listenTo(it) }
        registry.eventPublisher.onEntryAdded { event -> listenTo(event.addedEntry) }
    }

    private fun listenTo(breaker: CircuitBreaker) {
        breaker.eventPublisher.onStateTransition { event -> log(breaker, event) }
    }

    private fun log(breaker: CircuitBreaker, event: CircuitBreakerOnStateTransitionEvent) {
        val from = event.stateTransition.fromState
        val to = event.stateTransition.toState
        when (to) {
            CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN ->
                log.warn(
                    "Circuit breaker '{}' is now {} ({}): calls are rejected without being sent (failure rate {}%)",
                    breaker.name, to, from, breaker.metrics.failureRate
                )
            CircuitBreaker.State.CLOSED -> log.info("Circuit breaker '{}' is closed again ({})", breaker.name, from)
            else -> log.debug("Circuit breaker '{}' moved {} -> {}", breaker.name, from, to)
        }
    }
}
