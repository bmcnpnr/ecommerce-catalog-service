package com.ecommerce.catalogservice.contract

import au.com.dius.pact.consumer.dsl.LambdaDsl
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit.MockServerConfig
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.config.FeignConfig
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import com.ecommerce.catalogservice.service.CatalogService
import feign.FeignException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.cloud.openfeign.FeignAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.util.Optional

/**
 * Consumer side of the contract between catalog-service and product-service.
 *
 * catalog-service reaches product-service through [ProductServiceClient] (Feign,
 * resolved via Eureka as `product-service`). These tests run the *real* Feign
 * client — the production [FeignConfig] (converters, retryer) and Boot's Jackson
 * setup, which is what decodes product-service's JSON into the Kotlin data classes
 * — against a Pact mock server that plays product-service, and drive it through
 * [CatalogService.syncFromProductService] so the pact records exactly the response
 * fields the sync projects into a catalog entry.
 *
 * Running this class writes `target/pacts/catalog-service-product-service.json`.
 * That file is copied into product-service's `src/test/resources/pacts/` (see
 * `ecommerce-platform/sync-pacts.sh`) where `ProductServiceProviderPactTest`
 * replays every interaction against the real product-service.
 *
 * Note on the Kotlin DTOs: `ProductDTO.id/sku/name/price/stockQuantity/status` and
 * `CategoryDTO.id/name` are non-null, so a response without them fails to decode at
 * all — they are in the contract even where the sync does not read them (`status`).
 * `description`, `brand`, `categoryId`, `parentCategoryId` are nullable and read by
 * the sync; the pact pins their type when present.
 */
@SpringBootTest(
    classes = [ProductServiceConsumerPactTest.FeignOnlyConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        // Point the production Feign client (lb://product-service) at the Pact mock
        // server instead of the load balancer. The port is fixed because the Spring
        // context is built before Pact starts the mock server for each test.
        "spring.cloud.openfeign.client.config.product-service.url=http://localhost:${ProductServiceConsumerPactTest.MOCK_PORT}",
        "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
        "spring.cloud.openfeign.client.config.default.read-timeout=10000",
        // Pact restarts its mock server for every test method; a pooled keep-alive
        // connection from the previous test would be stale. Expire pooled
        // connections immediately so each call opens a fresh one (test-only).
        "spring.cloud.openfeign.httpclient.time-to-live=1",
        "spring.cloud.openfeign.httpclient.time-to-live-unit=MILLISECONDS",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
    ]
)
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "product-service", pactVersion = PactSpecVersion.V4)
@MockServerConfig(port = ProductServiceConsumerPactTest.MOCK_PORT)
class ProductServiceConsumerPactTest {

    companion object {
        const val CONSUMER = "catalog-service"
        const val MOCK_PORT = "18083"
    }

    /**
     * Just enough of the application to build the real Feign client: Spring Cloud
     * OpenFeign, Boot's HTTP message converters and Jackson, plus the service's own
     * [FeignConfig]. No MongoDB or Eureka.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(FeignAutoConfiguration::class, HttpMessageConvertersAutoConfiguration::class, JacksonAutoConfiguration::class)
    @EnableFeignClients(clients = [ProductServiceClient::class])
    @Import(FeignConfig::class, CatalogService::class)
    class FeignOnlyConfig

    @Autowired
    private lateinit var catalogService: CatalogService

    @MockitoBean
    private lateinit var catalogEntryRepository: CatalogEntryRepository

    // ───────────────────────────── pacts ─────────────────────────────

    @Pact(consumer = CONSUMER)
    fun syncReadsProductAndCategories(builder: PactDslWithProvider): V4Pact = builder
        .given("product 1 exists")
        .uponReceiving("a request for product 1")
            .method("GET")
            .path("/api/v1/products/1")
        .willRespondWith()
            .status(200)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(LambdaDsl.newJsonBody { product ->
                product
                    .integerType("id", 1)
                    .stringType("sku", "SKU-001")
                    .stringType("name", "Wireless Mouse")
                    .stringType("description", "Ergonomic wireless mouse")
                    // BigDecimal on both sides; any JSON number is acceptable.
                    .numberType("price", 49.99)
                    .integerType("stockQuantity", 50)
                    .stringType("brand", "Logi")
                    .integerType("categoryId", 10)
                    .stringType("status", "ACTIVE")
            }.build())
        .given("categories exist")
        .uponReceiving("a request for all categories")
            .method("GET")
            .path("/api/v1/categories")
        .willRespondWith()
            .status(200)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(LambdaDsl.newJsonArrayMinLike(1) { categories ->
                categories.`object` { category ->
                    category
                        .integerType("id", 10)
                        .stringType("name", "Electronics")
                        .stringType("description", "Devices and accessories")
                }
            }.build())
        .toPact(V4Pact::class.java)

    @Pact(consumer = CONSUMER)
    fun unknownProductIsNotFound(builder: PactDslWithProvider): V4Pact = builder
        .given("product 999 does not exist")
        .uponReceiving("a request for product 999")
            .method("GET")
            .path("/api/v1/products/999")
        .willRespondWith()
            .status(404)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(LambdaDsl.newJsonBody { error ->
                error
                    .integerType("status", 404)
                    .stringType("error", "Not Found")
                    .stringType("message", "Product not found with id: 999")
            }.build())
        .toPact(V4Pact::class.java)

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    @PactTestFor(pactMethod = "syncReadsProductAndCategories")
    fun `syncFromProductService projects the product and its category name into a catalog entry`() {
        `when`(catalogEntryRepository.findById(1L)).thenReturn(Optional.empty())
        `when`(catalogEntryRepository.save(any(CatalogEntry::class.java))).thenAnswer { it.getArgument(0) }

        val dto = catalogService.syncFromProductService(1L)

        val saved = ArgumentCaptor.forClass(CatalogEntry::class.java)
        verify(catalogEntryRepository).save(saved.capture())
        val entry = saved.value
        // Fields catalog-service copies out of product-service's responses.
        assertEquals(1L, entry.productId)
        assertEquals("SKU-001", entry.productSku)
        assertEquals("Wireless Mouse", entry.productName)
        assertEquals("Ergonomic wireless mouse", entry.description)
        assertEquals(0, BigDecimal("49.99").compareTo(entry.price))
        assertEquals("Logi", entry.brand)
        assertEquals(10L, entry.categoryId)
        assertEquals("Electronics", entry.categoryName)   // joined from /api/v1/categories by categoryId
        assertEquals(StockStatus.AVAILABLE, entry.stockStatus) // 50 units -> AVAILABLE (< 10 would be LOW_STOCK)
        assertEquals(1L, dto.productId)
    }

    @Test
    @PactTestFor(pactMethod = "unknownProductIsNotFound")
    fun `syncFromProductService propagates a 404 from product-service as FeignException NotFound`() {
        // Not caught by CatalogService; GlobalExceptionHandler's catch-all turns it into a 500
        // for the caller. The contract only pins that product-service answers 404 here.
        val ex = assertThrows<FeignException.NotFound> { catalogService.syncFromProductService(999L) }
        assertEquals(404, ex.status())
    }
}
