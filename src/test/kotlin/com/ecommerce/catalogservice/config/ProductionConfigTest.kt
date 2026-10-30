package com.ecommerce.catalogservice.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.time.Duration

/**
 * The resilience-relevant settings of the packaged `application.yaml`, read as the running service reads them
 * (no profile, no override). The ITs run with test overrides on purpose; this pins what production gets.
 */
class ProductionConfigTest {

    companion object {
        private lateinit var binder: Binder

        @BeforeAll
        @JvmStatic
        fun load() {
            val sources = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
            binder = Binder(ConfigurationPropertySources.from(sources))
        }

        private fun <T : Any> get(name: String, type: Class<T>): T =
            binder.bind(name, Bindable.of(type)).orElseThrow { AssertionError("application.yaml has no $name") }
    }

    @Test
    fun `spring cloud circuit breaker runs on the calling thread, with no time limiter and no bulkhead`() {
        // the defaults are a worker thread, a 1 s time limiter and a bulkhead of one thread per CPU with a 100-call queue
        assertTrue(get("spring.cloud.circuitbreaker.resilience4j.disable-thread-pool", Boolean::class.javaObjectType))
        assertTrue(get("spring.cloud.circuitbreaker.resilience4j.disable-time-limiter", Boolean::class.javaObjectType))
        assertFalse(get("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled", Boolean::class.javaObjectType))
        assertTrue(get("spring.cloud.openfeign.circuitbreaker.enabled", Boolean::class.javaObjectType))
    }

    @Test
    fun `feign timeouts are the real time limit`() {
        assertEquals(2000, get("spring.cloud.openfeign.client.config.default.connect-timeout", Int::class.javaObjectType))
        assertEquals(10000, get("spring.cloud.openfeign.client.config.default.read-timeout", Int::class.javaObjectType))
    }

    @Test
    fun `the breaker opens on failures and on slowness, and ignores client errors`() {
        val p = "resilience4j.circuitbreaker.instances.product-service."

        assertEquals(10, get(p + "sliding-window-size", Int::class.javaObjectType))
        assertEquals(5, get(p + "minimum-number-of-calls", Int::class.javaObjectType))
        assertEquals(50, get(p + "failure-rate-threshold", Int::class.javaObjectType))
        assertEquals(Duration.ofSeconds(10), get(p + "wait-duration-in-open-state", Duration::class.java))
        assertEquals(Duration.ofSeconds(5), get(p + "slow-call-duration-threshold", Duration::class.java))
        assertEquals(80, get(p + "slow-call-rate-threshold", Int::class.javaObjectType))
        assertTrue(get(p + "automatic-transition-from-open-to-half-open-enabled", Boolean::class.javaObjectType))
        val ignored = binder.bind(p + "ignore-exceptions", Bindable.listOf(String::class.java)).get()
        assertTrue(ignored.containsAll(listOf("feign.FeignException\$NotFound", "feign.FeignException\$BadRequest", "feign.FeignException\$Conflict")))
    }

    @Test
    fun `mongodb timeouts are explicit and short`() {
        assertEquals(Duration.ofSeconds(3), get("catalog.mongo.server-selection-timeout", Duration::class.java))
        assertEquals(Duration.ofSeconds(2), get("catalog.mongo.connect-timeout", Duration::class.java))
        assertEquals(Duration.ofSeconds(5), get("catalog.mongo.socket-timeout", Duration::class.java))
        assertEquals(Duration.ofSeconds(2), get("catalog.mongo.pool-wait-timeout", Duration::class.java))
    }

    @Test
    fun `logging is INFO in one format, with the drivers per-operation chatter off`() {
        val levels = binder.bind("logging.level", Bindable.mapOf(String::class.java, String::class.java)).get()

        assertEquals(
            "%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-}] [%X{traceId:-}] %-5level %logger{36} - %msg%n",
            get("logging.pattern.console", String::class.java)
        )
        assertEquals("INFO", levels["com.ecommerce"])
        assertEquals("WARN", levels["org.mongodb.driver"])
    }
}
