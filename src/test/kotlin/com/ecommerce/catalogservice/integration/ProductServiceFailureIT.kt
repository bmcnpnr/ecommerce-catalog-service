package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.model.CatalogEntry
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * What a sync caller sees when product-service misbehaves, through the real Feign
 * client and retryer.
 *
 * The client runs behind a circuit breaker (`spring.cloud.openfeign.circuitbreaker.enabled`).
 * A product-service 404 is passed through and answered as a 404; no answer at all (down,
 * timed out, breaker open, 503) is a 503 and an unusable answer (a 5xx, a body that cannot be
 * decoded) is a 502, each with a fixed message — and nothing is written. A failing categories
 * call only costs the category name. ProductServiceResilienceIT covers the timing, the breaker
 * and the retries.
 */
class ProductServiceFailureIT : AbstractCatalogIT() {

    private fun existingEntry(id: Long) = repository.save(
        CatalogEntry(
            productId = id, productSku = "SKU-$id", productName = "Before", price = BigDecimal("5.00"),
            featured = true, imageUrl = "http://img/$id.png"
        )
    )

    private fun assertUnavailable503(response: Response, path: String) {
        assertEquals(503, response.status, response.body)
        assertEquals("Service Unavailable", response.json["error"].asString())
        assertEquals("Service temporarily unavailable", response.json["message"].asString())
        assertEquals(path, response.json["path"].asString())
    }

    private fun assertBadGateway502(response: Response, path: String) {
        assertEquals(502, response.status, response.body)
        assertEquals("Bad Gateway", response.json["error"].asString())
        assertEquals("Upstream service error", response.json["message"].asString())
        assertEquals(path, response.json["path"].asString())
    }

    @Test
    fun `product-service answering 500 fails the sync with a 502 and leaves the stored entry untouched`() {
        existingEntry(51)
        productService.stubFor(get(urlEqualTo("/api/v1/products/51")).willReturn(aResponse().withStatus(500)))

        val response = call("POST", "/api/v1/catalog/sync/51")

        assertBadGateway502(response, "/api/v1/catalog/sync/51")
        // An HTTP error is not a connection failure: the retryer must not repeat it, and neither does the
        // HTTP client (its default strategy used to repeat a 503 once, after 1 s, underneath Feign).
        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/51")))
        val stored = repository.findById(51L).get()
        assertEquals("Before", stored.productName)
        assertTrue(stored.featured)
    }

    @Test
    fun `product-service down fails the sync with a 503 after the connection retries, writing nothing`() {
        val started = System.nanoTime()
        val response = withProductServiceDown { call("POST", "/api/v1/catalog/sync/52") }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertUnavailable503(response, "/api/v1/catalog/sync/52")
        assertFalse(repository.existsById(52L))
        // ConnectionFailureRetryer: 3 attempts with 100 ms and 200 ms back-off in between.
        assertTrue(elapsedMillis >= 300, "connection failures are retried with back-off; took only $elapsedMillis ms")
    }

    @Test
    fun `the sync works again as soon as product-service is back`() {
        withProductServiceDown { call("POST", "/api/v1/catalog/sync/53") }
        stubProduct(53)
        stubCategories()

        assertEquals(200, call("POST", "/api/v1/catalog/sync/53").status)
        assertTrue(repository.existsById(53L))
    }

    @Test
    fun `a failing categories call only costs the category name`() {
        stubProduct(54, categoryId = 5)
        productService.stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse().withStatus(500)))

        val response = call("POST", "/api/v1/catalog/sync/54")

        assertEquals(200, response.status, response.body)
        assertEquals(5L, response.json["categoryId"].asLong())
        assertTrue(response.json["categoryName"].isNull)
    }

    @Test
    fun `a response product-service cannot be decoded from is a 502, not a half-written entry`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/55")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"id":55}""")
            )
        )

        assertBadGateway502(call("POST", "/api/v1/catalog/sync/55"), "/api/v1/catalog/sync/55")
        assertFalse(repository.existsById(55L))
    }

    @Test
    fun `syncing a product that does not exist upstream is a 404`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/56")).willReturn(
                aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                    .withBody("""{"status":404,"error":"Not Found","message":"Product not found with id: 56"}""")
            )
        )

        val response = call("POST", "/api/v1/catalog/sync/56")

        assertEquals(404, response.status, response.body)
        assertEquals("Not Found", response.json["error"].asString())
        assertFalse(repository.existsById(56L))
    }
}
