package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.model.StockStatus
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Every endpoint of CatalogController over real HTTP: JSON binding, the shape of
 * CatalogEntryDTO and Page on the wire, the error envelope from
 * GlobalExceptionHandler and the X-Correlation-Id round trip.
 */
class CatalogApiIT : AbstractCatalogIT() {

    private fun sync(id: Long): Response = call("POST", "/api/v1/catalog/sync/$id")

    @Test
    fun `sync pulls the product from product-service and stores the projection`() {
        stubProduct(1, name = "Wireless Mouse", price = "19.9900", stockQuantity = 3)
        stubCategories()

        val response = sync(1)

        assertEquals(200, response.status, response.body)
        val body = response.json
        assertEquals(1L, body["id"].asLong())
        assertEquals(1L, body["productId"].asLong())
        assertEquals("SKU-1", body["productSku"].asString())
        assertEquals("Wireless Mouse", body["productName"].asString())
        assertEquals("A product", body["description"].asString())
        assertPrice("19.99", body["price"])
        assertEquals(BigDecimal("19.99"), repository.findById(1L).get().price, "NUMERIC(19,4) from product-service is stored rounded to cents")
        assertEquals("ACME", body["brand"].asString())
        assertEquals(5L, body["categoryId"].asLong())
        assertEquals("Electronics", body["categoryName"].asString())
        assertFalse(body["featured"].asBoolean())
        assertEquals(StockStatus.LOW_STOCK.name, body["stockStatus"].asString())
        assertTrue(body["imageUrl"].isNull)
        assertTrue(body["createdAt"].isString && body["updatedAt"].isString, "timestamps are ISO strings: ${response.body}")

        productService.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/1")))
        assertTrue(repository.existsById(1L))
    }

    @Test
    fun `a second sync updates the entry in place and keeps the catalog-owned fields`() {
        stubProduct(2, name = "Keyboard", stockQuantity = 50)
        stubCategories()
        sync(2)
        assertEquals(200, call("PATCH", "/api/v1/catalog/product/2", """{"featured":true,"imageUrl":"http://img/2.png"}""").status)

        stubProduct(2, name = "Mechanical Keyboard", stockQuantity = 0, price = "89.5")
        val response = sync(2)

        assertEquals(200, response.status, response.body)
        assertEquals("Mechanical Keyboard", response.json["productName"].asString())
        assertEquals(StockStatus.OUT_OF_STOCK.name, response.json["stockStatus"].asString())
        assertPrice("89.50", response.json["price"])
        assertTrue(response.json["featured"].asBoolean())
        assertEquals("http://img/2.png", response.json["imageUrl"].asString())
        assertEquals(1, repository.count())
    }

    @Test
    fun `get by product id returns the stored entry`() {
        stubProduct(3)
        stubCategories()
        sync(3)

        val response = call("GET", "/api/v1/catalog/product/3")

        assertEquals(200, response.status)
        assertEquals(3L, response.json["productId"].asLong())
        assertEquals("Product 3", response.json["productName"].asString())
    }

    @Test
    fun `an unknown product is a 404 in the error envelope, carrying the caller's correlation id`() {
        val response = call("GET", "/api/v1/catalog/product/404404", headers = mapOf("X-Correlation-Id" to "corr-it-1"))

        assertEquals(404, response.status)
        assertEquals("corr-it-1", response.headers.firstValue("X-Correlation-Id").orElse(null))
        val body = response.json
        assertEquals(404, body["status"].asInt())
        assertEquals("Not Found", body["error"].asString())
        assertEquals("Catalog entry not found for product 404404", body["message"].asString())
        assertEquals("/api/v1/catalog/product/404404", body["path"].asString())
        assertEquals("corr-it-1", body["correlationId"].asString())
        assertTrue(body["fieldErrors"].isEmpty)
    }

    @Test
    fun `a request without a correlation id gets a generated one`() {
        val response = call("GET", "/api/v1/catalog")

        val id = response.headers.firstValue("X-Correlation-Id").orElse(null)
        assertNotNull(id)
        assertDoesNotThrow { UUID.fromString(id) }
    }

    @Test
    fun `browse pages through all entries`() {
        stubCategories()
        (11L..13L).forEach { stubProduct(it); sync(it) }

        val first = call("GET", "/api/v1/catalog?page=0&size=2").json
        val second = call("GET", "/api/v1/catalog?page=1&size=2").json

        assertEquals(3, first["totalElements"].asInt())
        assertEquals(2, first["content"].size())
        assertEquals(1, second["content"].size())
        val ids = (first["content"] + second["content"]).map { it["productId"].asLong() }.toSet()
        assertEquals(setOf(11L, 12L, 13L), ids)
    }

    @Test
    fun `by category returns only that category's entries`() {
        stubCategories()
        stubProduct(21, categoryId = 5)
        stubProduct(22, categoryId = 6)
        sync(21); sync(22)

        val response = call("GET", "/api/v1/catalog/category/6")

        assertEquals(200, response.status)
        assertEquals(listOf(22L), response.json.map { it["productId"].asLong() })
        assertEquals("Books", response.json[0]["categoryName"].asString())
    }

    @Test
    fun `featured lists only entries an admin featured through PATCH`() {
        stubCategories()
        stubProduct(31); stubProduct(32)
        sync(31); sync(32)

        val patch = call("PATCH", "/api/v1/catalog/product/32", """{"featured":true,"price":12.345}""")
        assertEquals(200, patch.status, patch.body)
        assertPrice("12.35", patch.json["price"])
        assertEquals(BigDecimal("12.35"), repository.findById(32L).get().price, "PATCH prices are rounded to cents too")

        val featured = call("GET", "/api/v1/catalog/featured").json
        assertEquals(listOf(32L), featured.map { it["productId"].asLong() })
    }

    @Test
    fun `PATCH of an unknown entry is a 404`() {
        val response = call("PATCH", "/api/v1/catalog/product/999999", """{"featured":true}""")

        assertEquals(404, response.status)
        assertEquals("Not Found", response.json["error"].asString())
    }

    @Test
    fun `search ranks text hits and falls back to a substring match for partial words`() {
        stubCategories()
        stubProduct(41, name = "Wireless Mouse", description = "Ergonomic")
        stubProduct(42, name = "Desk Lamp", description = "Works great next to a wireless keyboard")
        stubProduct(43, name = "Coffee Mug", description = "Ceramic")
        listOf(41L, 42L, 43L).forEach { sync(it) }

        val ranked = call("GET", "/api/v1/catalog/search?q=wireless").json
        assertEquals(
            listOf(41L, 42L), ranked["content"].map { it["productId"].asLong() },
            "a name hit (weight 10) outranks a description hit (weight 1)"
        )

        val partial = call("GET", "/api/v1/catalog/search?q=wirel").json
        assertEquals(listOf(41L), partial["content"].map { it["productId"].asLong() }, "substring fallback on the name")

        val none = call("GET", "/api/v1/catalog/search?q=nothing-matches-this").json
        assertEquals(0, none["totalElements"].asInt())
    }

    @Test
    fun `malformed requests are client errors, not server errors`() {
        // GlobalExceptionHandler's catch-all @ExceptionHandler(Exception::class) also catches
        // Spring MVC's own client-error exceptions before DefaultHandlerExceptionResolver can
        // map them to 400.
        assertAll(
            { assertEquals(400, call("GET", "/api/v1/catalog/product/abc").status, "non-numeric path variable") },
            { assertEquals(400, call("GET", "/api/v1/catalog/search").status, "missing required q") },
            { assertEquals(400, call("GET", "/api/v1/catalog?size=0").status, "page size below 1") },
            { assertEquals(400, call("PATCH", "/api/v1/catalog/product/1", """{"featured": """).status, "unreadable JSON body") }
        )
    }
}
