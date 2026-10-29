package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * Authorization effect on stored data, stored XSS, URL and price validation, mass
 * assignment, error-body hygiene and headers - over real HTTP against a real mongod.
 *
 * Feign / SSRF: not applicable. The only outbound calls are `GET /api/v1/products/{id}`
 * and `GET /api/v1/categories` on the `product-service` client, whose base URL comes from
 * configuration; the only caller-controlled part is a `Long` path variable, which
 * [CatalogInjectionSecurityIT] shows is type-checked. No parameter selects a host or URL.
 */
@Tag("security")
class CatalogDataIntegritySecurityIT : AbstractCatalogIT() {

    private fun seed(id: Long, name: String = "Product $id") = repository.save(
        CatalogEntry(
            productId = id, productSku = "SKU-$id", productName = name, price = BigDecimal("10.00"),
            brand = "ACME", categoryId = 5, categoryName = "Electronics", imageUrl = "https://img.example/$id.png"
        )
    )

    /** Like [call] but with a caller-chosen Content-Type (call() always sends application/json with a body). */
    private fun callAs(method: String, path: String, body: String, contentType: String): Response {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port$path"))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", contentType)
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
        val response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.headers(), response.body())
    }

    private fun patch(id: Long, body: String) = call("PATCH", "/api/v1/catalog/product/$id", body)

    // ───────────────────────── authorization (S-1), with database proof ─────────────────────────

    @Test
    fun `customer cannot modify the catalog and the database is untouched`() {
        seed(1)
        stubProduct(2)
        stubCategories()
        val customer = mapOf("X-User-Role" to "CUSTOMER", "X-Username" to "mallory")

        assertEquals(403, call("POST", "/api/v1/catalog/sync/2", headers = customer).status)
        assertEquals(403, call("PATCH", "/api/v1/catalog/product/1", """{"featured":true}""", customer).status)
        assertEquals(403, call("PATCH", "/api/v1/catalog/product/1", """{"imageUrl":"https://evil.example/x.png"}""", customer).status)
        assertEquals(403, call("PATCH", "/api/v1/catalog/product/1", """{"price":0.01}""", customer).status)
        // no identity at all is 401 (spec), a non-admin identity is 403
        assertEquals(401, call("PATCH", "/api/v1/catalog/product/1", """{"price":0.01}""", asAdmin = false).status)
        assertEquals(401, call("POST", "/api/v1/catalog/sync/2", asAdmin = false).status)

        val stored = repository.findById(1L).get()
        assertFalse(stored.featured)
        assertEquals("https://img.example/1.png", stored.imageUrl)
        assertEquals(0, BigDecimal("10.00").compareTo(stored.price))
        assertFalse(repository.existsById(2L), "a customer-triggered sync must not create an entry")
    }

    @Test
    fun `reads are public without identity headers`() {
        seed(1)
        for (path in listOf("/api/v1/catalog", "/api/v1/catalog/search?q=product", "/api/v1/catalog/product/1",
            "/api/v1/catalog/category/5", "/api/v1/catalog/featured")) {
            assertEquals(200, call("GET", path).status, path)
        }
    }

    // ───────────────────────── stored XSS / output encoding ─────────────────────────

    private val xssPayloads = listOf(
        "<script>alert(1)</script>", "<img src=x onerror=alert(document.cookie)>", "\"><svg/onload=alert(1)>",
        "javascript:alert(1)", "</script><script>alert(2)</script>"
    )

    @Test
    fun `markup in product data round-trips as inert JSON text`() {
        stubCategories()
        xssPayloads.forEachIndexed { i, payload ->
            val id = 100L + i
            productService.stubFor(
                get(urlEqualTo("/api/v1/products/$id")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        json.writeValueAsString(
                            mapOf(
                                "id" to id, "sku" to "XSS-$i", "name" to payload, "description" to payload, "price" to 5.0,
                                "stockQuantity" to 5, "brand" to payload, "categoryId" to 5, "status" to "ACTIVE",
                                "createdAt" to "2026-08-23T14:30:15", "updatedAt" to "2026-08-23T14:30:15"
                            )
                        )
                    )
                )
            )
            val r = call("POST", "/api/v1/catalog/sync/$id")
            assertEquals(200, r.status, r.body)
            assertTrue(r.headers.firstValue("Content-Type").orElse("").startsWith("application/json"), "sync content type")
            assertEquals(payload, r.json["productName"].asString())
            assertEquals(payload, repository.findById(id).get().productName, "stored verbatim")
        }
        for (path in listOf("/api/v1/catalog", "/api/v1/catalog/product/100", "/api/v1/catalog/search?q=script")) {
            val r = call("GET", path, headers = mapOf("Accept" to "text/html, application/json"))
            assertEquals(200, r.status, path)
            assertTrue(r.headers.firstValue("Content-Type").orElse("").startsWith("application/json"), "$path content type")
        }
    }

    @Test
    fun `error responses that echo input are JSON, not HTML`() {
        val r = call("GET", "/api/v1/catalog/product/%3Cimg%20onerror=alert(1)%3E", headers = mapOf("Accept" to "text/html, application/json"))
        assertTrue(r.status in 400..499, "${r.status}")
        assertTrue(r.headers.firstValue("Content-Type").orElse("").startsWith("application/json"), "content type was ${r.headers.firstValue("Content-Type").orElse("")}")
    }

    @Test
    fun `json responses send nosniff`() {
        seed(1, "<script>alert(1)</script>")
        val r = call("GET", "/api/v1/catalog/product/1")
        assertEquals("nosniff", r.headers.firstValue("X-Content-Type-Options").orElse(null))
    }

    // ───────────────────────── imageUrl ─────────────────────────

    @ParameterizedTest(name = "imageUrl {0} is rejected")
    @ValueSource(
        strings = ["javascript:alert(document.cookie)", "JaVaScRiPt:alert(1)", "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)", "file:///etc/passwd", "ftp://evil.example/x", "//evil.example/x.png", "  javascript:alert(1)", "http://"]
    )
    fun `dangerous image URL schemes are rejected`(url: String) {
        seed(1)

        val r = patch(1, """{"imageUrl":${json.writeValueAsString(url)}}""")

        assertEquals(400, r.status, "imageUrl=$url -> ${r.body}")
        assertEquals("https://img.example/1.png", repository.findById(1L).get().imageUrl)
    }

    @Test
    fun `an enormous image URL is rejected`() {
        seed(1)

        val r = patch(1, """{"imageUrl":"https://img.example/${"a".repeat(1_000_000)}"}""")

        assertTrue(r.status in listOf(400, 413), "status ${r.status}")
        assertEquals("https://img.example/1.png", repository.findById(1L).get().imageUrl)
    }

    @Test
    fun `ordinary http and https image URLs are accepted`() {
        seed(1)
        assertEquals(200, patch(1, """{"imageUrl":"https://cdn.example/a.png?x=1&y=2"}""").status)
        assertEquals("https://cdn.example/a.png?x=1&y=2", repository.findById(1L).get().imageUrl)
        assertEquals(200, patch(1, """{"imageUrl":"http://img.example/b.png"}""").status)
    }

    // ───────────────────────── price tampering ─────────────────────────

    @ParameterizedTest(name = "PATCH price {0} is rejected and the stored price is unchanged")
    @ValueSource(strings = ["-1", "-0.01", "0", "0.00", "0.004", "-99999999999"])
    fun `non-positive prices are rejected`(price: String) {
        seed(1)

        val r = patch(1, """{"price":$price}""")

        assertEquals(400, r.status, "price=$price -> ${r.body}")
        assertEquals(0, BigDecimal("10.00").compareTo(repository.findById(1L).get().price))
    }

    @ParameterizedTest(name = "PATCH price literal {0} is rejected")
    @ValueSource(strings = ["\"NaN\"", "\"Infinity\"", "\"abc\"", "[1]", "{}", "\"1e\""])
    fun `non-numeric price literals are rejected`(literal: String) {
        seed(1)

        val r = patch(1, """{"price":$literal}""")

        assertEquals(400, r.status, "price=$literal -> ${r.body}")
        assertEquals(0, BigDecimal("10.00").compareTo(repository.findById(1L).get().price))
    }

    @Test
    fun `absurdly large prices are rejected`() {
        seed(1)

        for (price in listOf("1E+400", "99999999999999999999999999999999999999999")) {
            val r = patch(1, """{"price":$price}""")
            assertEquals(400, r.status, "price=$price -> ${r.body}")
        }
        assertEquals(0, BigDecimal("10.00").compareTo(repository.findById(1L).get().price))
    }

    @Test
    fun `a valid price update is stored in cents and a sub-cent price is rejected, not rounded`() {
        seed(1)
        assertEquals(200, patch(1, """{"price":12.35}""").status)
        assertEquals(BigDecimal("12.35"), repository.findById(1L).get().price)
        assertEquals(400, patch(1, """{"price":12.345}""").status)
        assertEquals(BigDecimal("12.35"), repository.findById(1L).get().price)
    }

    // ───────────────────────── mass assignment ─────────────────────────

    @Test
    fun `PATCH cannot change identity, projected or server-controlled fields`() {
        val before = seed(1)
        val body = """
            {"featured":true,"productId":999,"id":999,"productSku":"HACKED","productName":"Hacked","description":"hacked",
             "brand":"HACKED","categoryId":99,"categoryName":"HACKED","stockStatus":"OUT_OF_STOCK",
             "createdAt":"1999-01-01T00:00:00","updatedAt":"1999-01-01T00:00:00","version":12345}
        """.trimIndent()

        val r = patch(1, body)

        assertEquals(200, r.status, r.body)
        val after = repository.findById(1L).get()
        assertTrue(after.featured, "the legitimate field is applied")
        assertEquals("SKU-1", after.productSku)
        assertEquals("Product 1", after.productName)
        assertNull(after.description)
        assertEquals("ACME", after.brand)
        assertEquals(5L, after.categoryId)
        assertEquals("Electronics", after.categoryName)
        assertEquals(StockStatus.AVAILABLE, after.stockStatus)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt.year >= 2026, "updatedAt stays server-controlled: ${after.updatedAt}")
        assertFalse(repository.existsById(999L), "no document under a caller-chosen id")
        assertEquals(1, repository.count())
        assertEquals(1L, r.json["productId"].asLong())
    }

    // ───────────────────────── error handling ─────────────────────────

    private val leakMarkers = listOf(
        "java.", "javax.", "jakarta.", "kotlin.", "com.ecommerce", "org.springframework", "org.mongodb", "com.mongodb", "org.bson",
        "tools.jackson", "com.fasterxml", "Exception", "\tat ", "stackTrace", "mongo", "MongoDB"
    )

    private fun assertNoInternals(body: String) {
        for (marker in leakMarkers) {
            assertFalse(body.contains(marker), "error body must not contain '$marker': $body")
        }
    }

    @Test
    fun `unexpected failure answers a generic 500 without internals`() {
        stubProduct(5)
        productService.stubFor(
            get(urlEqualTo("/api/v1/products/6")).willReturn(
                aResponse().withStatus(500).withBody("com.ecommerce.product.SecretException: jdbc:postgresql://productdb:5432 password=productpass at line 1")
            )
        )

        val r = call("POST", "/api/v1/catalog/sync/6")

        assertEquals(500, r.status, r.body)
        assertFalse(r.body.contains("productpass"))
        assertFalse(r.body.contains("jdbc"))
        assertNoInternals(r.body)
    }

    @Test
    fun `malformed JSON is a 400`() {
        seed(1)
        val r = patch(1, """{"featured": tru""")
        assertEquals(400, r.status, r.body)
    }

    @Test
    fun `client error bodies do not reveal parser text or type names`() {
        seed(1)
        val bodies = listOf(
            patch(1, """{"featured": tru"""),
            patch(1, """{"price":"abc"}"""),
            patch(1, """{"featured":{"${'$'}ne":false}}"""),
            call("GET", "/api/v1/catalog/product/abc"),
            call("GET", "/api/v1/catalog/category/abc")
        )
        for (r in bodies) {
            assertEquals(400, r.status, r.body)
            assertNoInternals(r.body)
        }
    }

    @Test
    fun `wrong content type is a 415 and an unsupported method a 405`() {
        seed(1)
        val text = callAs("PATCH", "/api/v1/catalog/product/1", "featured=true", "text/plain")
        assertEquals(415, text.status, text.body)
        assertFalse(repository.findById(1L).get().featured)

        assertEquals(405, call("DELETE", "/api/v1/catalog/product/1").status)
        assertEquals(405, call("PUT", "/api/v1/catalog/product/1", "{}").status)
        assertEquals(1, repository.count())
    }

    @Test
    fun `xml bodies with external entities are rejected`() {
        seed(1)
        val r = callAs(
            "PATCH", "/api/v1/catalog/product/1",
            """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><r><imageUrl>&e;</imageUrl></r>""",
            "application/xml"
        )
        assertEquals(415, r.status, r.body)
        assertEquals("https://img.example/1.png", repository.findById(1L).get().imageUrl)
    }

    @Test
    fun `unknown paths are 404 not 500`() {
        assertEquals(404, call("GET", "/api/v1/catalog/nope/nothing").status)
        assertEquals(404, call("GET", "/api/v1/nope").status)
    }

    // ───────────────────────── headers ─────────────────────────

    @Test
    fun `a benign correlation id is echoed once`() {
        val r = call("GET", "/api/v1/catalog", headers = mapOf("X-Correlation-Id" to "abc-123"))
        assertEquals(listOf("abc-123"), r.headers.allValues("X-Correlation-Id"))
    }

    @Test
    fun `hostile or oversized correlation ids are replaced`() {
        val hostile = "x <script>alert(1)</script> 2026-10-29 ERROR forged log line"
        val r = call("GET", "/api/v1/catalog", headers = mapOf("X-Correlation-Id" to hostile))
        assertNotEquals(hostile, r.headers.firstValue("X-Correlation-Id").orElse(null))

        val big = call("GET", "/api/v1/catalog", headers = mapOf("X-Correlation-Id" to "a".repeat(5000)))
        assertTrue(big.headers.firstValue("X-Correlation-Id").orElse("").length <= 128)
    }
}
