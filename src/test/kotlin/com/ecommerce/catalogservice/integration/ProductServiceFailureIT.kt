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
 * A product-service 404 is passed through and answered as a 404; any other failure goes
 * through ProductServiceClientFallback, whose exception GlobalExceptionHandler's catch-all
 * turns into a generic 500 — and nothing is written. A failing categories call only
 * costs the category name.
 */
class ProductServiceFailureIT : AbstractCatalogIT() {

    private fun existingEntry(id: Long) = repository.save(
        CatalogEntry(
            productId = id, productSku = "SKU-$id", productName = "Before", price = BigDecimal("5.00"),
            featured = true, imageUrl = "http://img/$id.png"
        )
    )

    private fun assertGeneric500(response: Response, path: String) {
        assertEquals(500, response.status, response.body)
        assertEquals("Internal Server Error", response.json["error"].asString())
        assertEquals("An unexpected error occurred", response.json["message"].asString())
        assertEquals(path, response.json["path"].asString())
    }

    @Test
    fun `product-service answering 500 fails the sync with a 500 and leaves the stored entry untouched`() {
        existingEntry(51)
        productService.stubFor(get(urlEqualTo("/api/v1/products/51")).willReturn(aResponse().withStatus(500)))

        val response = call("POST", "/api/v1/catalog/sync/51")

        assertGeneric500(response, "/api/v1/catalog/sync/51")
        // An HTTP error is not a connection failure: the retryer must not repeat it. (A 503
        // would be repeated once, after 1 s, by Apache HttpClient 5's default retry strategy
        // underneath Feign - not by FeignConfig.)
        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/51")))
        val stored = repository.findById(51L).get()
        assertEquals("Before", stored.productName)
        assertTrue(stored.featured)
    }

    @Test
    fun `product-service down fails the sync with a 500 after the connection retries, writing nothing`() {
        val started = System.nanoTime()
        val response = withProductServiceDown { call("POST", "/api/v1/catalog/sync/52") }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertGeneric500(response, "/api/v1/catalog/sync/52")
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
    fun `a response product-service cannot be decoded from is a 500, not a half-written entry`() {
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/55")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"id":55}""")
            )
        )

        assertGeneric500(call("POST", "/api/v1/catalog/sync/55"), "/api/v1/catalog/sync/55")
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
