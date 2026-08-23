package com.ecommerce.catalogservice.repository

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

/**
 * Stands in for [com.ecommerce.catalogservice.CatalogServiceApplication] in
 * the `@DataMongoTest` slice.
 *
 * Slice tests look for the nearest `@SpringBootConfiguration`, starting in the
 * test's own package and walking up, so this class wins over the real
 * application class for tests in this package. The real one carries
 * `@EnableFeignClients`, whose client beans require the Feign auto-configuration
 * that the Mongo slice deliberately leaves out, so the context would fail to
 * start. `@EnableAutoConfiguration` is what registers this package as the
 * repository-scan root; the slice itself still restricts *which*
 * auto-configurations load.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class MongoSliceTestConfiguration
