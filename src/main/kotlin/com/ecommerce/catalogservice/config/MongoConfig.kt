package com.ecommerce.catalogservice.config

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

@Configuration(proxyBeanMethods = false)
class MongoConfig {

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
