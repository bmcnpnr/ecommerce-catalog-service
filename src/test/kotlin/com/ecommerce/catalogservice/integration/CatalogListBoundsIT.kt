package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.model.CatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * D3: no endpoint answers with more than one page of entries, however many there are, and pages are stable.
 * Category and featured used to return every matching entry in one unbounded array.
 */
class CatalogListBoundsIT : AbstractCatalogIT() {

    @BeforeEach
    fun seed() {
        // 150 entries in category 5 (the first 120 featured), 30 in category 6
        repository.saveAll(
            (1L..180L).map { id ->
                CatalogEntry(
                    productId = id, productSku = "SKU-$id", productName = "Bulk item $id", price = BigDecimal("1.00"),
                    categoryId = if (id <= 150) 5 else 6, featured = id <= 120
                )
            }
        )
    }

    private fun ids(path: String): List<Long> {
        val response = call("GET", path)
        assertEquals(200, response.status, "$path -> ${response.body}")
        val array: JsonNode = if (response.json.isArray) response.json else response.json["content"]
        return array.values().map { it["productId"].asLong() }.toList()
    }

    @Test
    fun `a category answers with one page of at most 100, in product id order`() {
        val first = ids("/api/v1/catalog/category/5")
        val second = ids("/api/v1/catalog/category/5?page=1&size=100")

        assertEquals((1L..100L).toList(), first, "150 entries, the default page holds 100")
        assertEquals((101L..150L).toList(), second)
    }

    @Test
    fun `the featured list is paged too`() {
        assertEquals((1L..100L).toList(), ids("/api/v1/catalog/featured"))
        assertEquals((101L..120L).toList(), ids("/api/v1/catalog/featured?page=1"))
        assertEquals((11L..20L).toList(), ids("/api/v1/catalog/featured?page=1&size=10"))
    }

    @Test
    fun `browse pages are stable and do not overlap`() {
        val pages = (0..3).map { ids("/api/v1/catalog?page=$it&size=50") }

        assertEquals((1L..180L).toList(), pages.flatten(), "every entry exactly once, in order, across pages")
    }

    @Test
    fun `oversized and nonsense pages are client errors`() {
        for (query in listOf("size=101", "size=0", "size=-1", "page=-1", "size=2147483647", "page=2147483647&size=100", "size=abc")) {
            for (path in listOf("/api/v1/catalog/category/5", "/api/v1/catalog/featured", "/api/v1/catalog")) {
                assertEquals(400, call("GET", "$path?$query").status, "$path?$query")
            }
        }
    }

    @Test
    fun `search text longer than any product name is a client error, 200 characters is fine`() {
        val tooLong = call("GET", "/api/v1/catalog/search?q=${"a".repeat(201)}")
        assertEquals(400, tooLong.status, tooLong.body)
        assertEquals("Invalid value for parameter 'q'", tooLong.json["message"].asString())

        assertEquals(200, call("GET", "/api/v1/catalog/search?q=${"a".repeat(200)}").status)
    }

    @Test
    fun `search results page stably too, text hits and substring fallback alike`() {
        // "item" is a whole word in every name (text index); "tem 1" only matches as a substring (regex fallback)
        val byText = (0..3).map { ids("/api/v1/catalog/search?q=item&page=$it&size=50") }.flatten()
        val bySubstring = (0..3).map { ids("/api/v1/catalog/search?q=tem%201&page=$it&size=50") }.flatten()

        assertEquals(180, byText.toSet().size, "every entry once across pages: ${byText.size} results")
        assertEquals(byText.size, byText.toSet().size)
        assertTrue(bySubstring.isNotEmpty() && bySubstring.size == bySubstring.toSet().size)
        assertEquals(bySubstring.sorted(), bySubstring, "the fallback is in product id order")
    }

    @Test
    fun `a request body over 1 MB is refused before anything parses it`() {
        val before = repository.count()

        val response = call("PATCH", "/api/v1/catalog/product/1", """{"imageUrl":"http://img/${"x".repeat(2 * 1024 * 1024)}"}""")

        assertEquals(413, response.status)
        assertEquals("Request body too large", response.json["message"].asString())
        assertEquals(before, repository.count())
    }
}
