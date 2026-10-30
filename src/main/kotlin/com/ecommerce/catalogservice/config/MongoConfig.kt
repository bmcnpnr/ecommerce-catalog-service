package com.ecommerce.catalogservice.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
class MongoConfig {

    /**
     * Explicit client timeouts (D4). The driver's defaults are a 30 s server-selection timeout, which makes every
     * request hang for half a minute through an outage (and pins a request thread each), no connect or read
     * timeout at all on an established connection, and a pool that waits 2 minutes for a free connection.
     * Here a request waits at most [serverSelection] for a server, [connect] to open a connection, [socket] for
     * the answer to a command and [poolWait] for a pooled connection, then fails with a MongoTimeoutException,
     * which the exception handler answers with a 503.
     *
     * A customizer rather than URI options so that SPRING_MONGODB_URI (which replaces host, port and credentials)
     * does not lose them.
     */
    @Bean
    fun mongoClientTimeouts(
        @Value("\${catalog.mongo.server-selection-timeout:3s}") serverSelection: Duration,
        @Value("\${catalog.mongo.connect-timeout:2s}") connect: Duration,
        @Value("\${catalog.mongo.socket-timeout:5s}") socket: Duration,
        @Value("\${catalog.mongo.pool-wait-timeout:2s}") poolWait: Duration
    ): MongoClientSettingsBuilderCustomizer = MongoClientSettingsBuilderCustomizer { builder ->
        builder.applyToClusterSettings { it.serverSelectionTimeout(serverSelection.toMillis(), TimeUnit.MILLISECONDS) }
        builder.applyToSocketSettings {
            it.connectTimeout(connect.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(socket.toMillis(), TimeUnit.MILLISECONDS)
        }
        builder.applyToConnectionPoolSettings { it.maxWaitTime(poolWait.toMillis(), TimeUnit.MILLISECONDS) }
    }

    companion object {
        /**
         * Stops Spring Data from writing its `_class` type hint into every
         * document. The hint only matters when one collection holds several
         * entity types (polymorphic reads); `catalog_entries` holds exactly
         * one, so it was pure noise and a leak of a Java class name into the
         * data. A post-processor rather than a replacement `MappingMongoConverter`
         * bean, so Boot's own converter — with the Decimal128 BigDecimal
         * representation and codec registry it configures — stays untouched.
         * Static (`@JvmStatic`): BeanPostProcessors must be created before the
         * beans they process.
         */
        @Bean
        @JvmStatic
        fun mongoTypeHintRemover(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (bean is MappingMongoConverter) {
                    bean.setTypeMapper(DefaultMongoTypeMapper(null))
                }
                return bean
            }
        }
    }
}
