package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.repository.CatalogEntryRepository
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.test.context.ContextConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.mongodb.MongoDBContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Shared harness for the integration tests: the whole application on a random port,
 * against a real mongod of the version the platform deploys, with product-service
 * played by a WireMock server — so requests go through the real controller, filter
 * and exception handler, and the sync through the real Feign client, `FeignConfig`
 * (converters, connection-failure retryer) and Boot's Jackson.
 *
 * MongoDB and WireMock are JVM-wide singletons started once (Ryuk removes the
 * container when the JVM exits) and every subclass has the same context
 * configuration, so all IT classes share one container, one stub server and one
 * cached Spring context. WireMock listens on a port fixed at class-load time, not a
 * dynamic one, so [restartProductService] can bring it back where Feign expects it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // Every test opens fresh connections: a pooled keep-alive connection to a
        // WireMock instance that a test stopped would be stale (test-only, as in the
        // Pact test).
        "spring.cloud.openfeign.httpclient.time-to-live=1",
        "spring.cloud.openfeign.httpclient.time-to-live-unit=MILLISECONDS",
        // Nothing to export traces to.
        "management.tracing.sampling.probability=0"
    ]
)
@ActiveProfiles("test")
@ContextConfiguration(initializers = [AbstractCatalogIT.ExcludeOtherTestConfigurations::class])
@Timeout(value = 2, unit = TimeUnit.MINUTES)
abstract class AbstractCatalogIT {

    /**
     * The application's component scan covers `com.ecommerce.catalogservice`, which on
     * the test classpath also holds the other tests' hand-made configurations: the
     * Pact test's FeignOnlyConfig (its own `@EnableFeignClients` registers
     * product-service a second time and the context fails on the duplicate
     * FeignClientSpecification) and the Mongo slice's stand-in application class.
     * Neither belongs in the full application; this keeps them out of the scan.
     */
    class ExcludeOtherTestConfigurations : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            context.beanFactory.registerSingleton("excludeOtherTestConfigurations", object : TypeExcludeFilter() {
                override fun match(reader: MetadataReader, factory: MetadataReaderFactory): Boolean {
                    val name = reader.classMetadata.className
                    return name.startsWith("com.ecommerce.catalogservice.contract.") ||
                        name == "com.ecommerce.catalogservice.repository.MongoSliceTestConfiguration"
                }
            })
        }
    }

    companion object {
        @ServiceConnection
        @JvmStatic
        val mongo: MongoDBContainer = MongoDBContainer("mongo:8.0").apply { start() }

        /** Fixed for the JVM so a stopped product-service can be restarted on the same address. */
        val productServicePort: Int = ServerSocket(0).use { it.localPort }

        var productService: WireMockServer = startProductService()
            private set

        private fun startProductService() =
            WireMockServer(options().port(productServicePort)).apply { start() }

        /** Simulates product-service being down (connection refused) for the duration of [block]. */
        fun <T> withProductServiceDown(block: () -> T): T {
            productService.stop()
            try {
                return block()
            } finally {
                productService = startProductService()
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun productServiceUrl(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.openfeign.client.config.product-service.url") { "http://localhost:$productServicePort" }
        }

        val json: JsonMapper = JsonMapper.builder().build()
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    @Value("\${local.server.port}")
    protected var port: Int = 0

    @Autowired
    protected lateinit var repository: CatalogEntryRepository

    @BeforeEach
    fun resetState() {
        repository.deleteAll()
        productService.resetAll()
    }

    class Response(val status: Int, val headers: java.net.http.HttpHeaders, val body: String) {
        val json: JsonNode by lazy { AbstractCatalogIT.json.readTree(body) }
    }

    /**
     * Writes (anything but GET) are sent as the gateway would forward an administrator unless [asAdmin] is
     * false or [headers] sets its own `X-User-Role`.
     */
    protected fun call(
        method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(),
        asAdmin: Boolean = method != "GET"
    ): Response {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(60))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        val effective = (if (asAdmin) mapOf("X-User-Role" to "ADMIN") else emptyMap()) + headers
        effective.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.headers(), response.body())
    }

    /** Numeric equality of a JSON price, independent of how the parser scales the number. */
    protected fun assertPrice(expected: String, actual: JsonNode) {
        org.junit.jupiter.api.Assertions.assertEquals(
            0, java.math.BigDecimal(expected).compareTo(actual.decimalValue()), "price $actual, expected $expected"
        )
    }

    /** Stubs `GET /api/v1/products/{id}` with a product shaped as in the committed pact. */
    protected fun stubProduct(
        id: Long,
        name: String = "Product $id",
        price: String = "19.9900",
        stockQuantity: Int = 25,
        brand: String = "ACME",
        description: String = "A product",
        categoryId: Long = 5
    ) {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/$id")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {"id":$id,"sku":"SKU-$id","name":"$name","description":"$description","price":$price,
                     "stockQuantity":$stockQuantity,"brand":"$brand","categoryId":$categoryId,"status":"ACTIVE",
                     "createdAt":"2026-08-23T14:30:15","updatedAt":"2026-08-23T14:30:15"}
                    """.trimIndent()
                )
            )
        )
    }

    protected fun stubCategories() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/categories")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """[{"id":5,"name":"Electronics","description":"Gadgets","parentCategoryId":null},
                        {"id":6,"name":"Books","description":"Paper","parentCategoryId":null}]"""
                )
            )
        )
    }
}
