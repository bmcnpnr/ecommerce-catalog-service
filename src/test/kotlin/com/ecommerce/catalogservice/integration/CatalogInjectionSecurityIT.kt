package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.model.CatalogEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration

/**
 * NoSQL-injection attempts against a real mongod. Every input is data: no operator in a
 * query string, path variable or JSON body may change the query the service runs, search
 * input is matched literally (no regex semantics, no ReDoS), and nothing answers 500.
 */
@Tag("security")
class CatalogInjectionSecurityIT : AbstractCatalogIT() {

    private fun seed(id: Long, name: String, featured: Boolean = false, categoryId: Long? = 5) =
        repository.save(
            CatalogEntry(
                productId = id, productSku = "SKU-$id", productName = name, price = BigDecimal("10.00"),
                brand = "ACME", categoryId = categoryId, featured = featured
            )
        )

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun search(q: String) = call("GET", "/api/v1/catalog/search?q=${enc(q)}")

    @BeforeEach
    fun seedCatalog() {
        seed(1, "Wireless Mouse")
        seed(2, "Wired Keyboard")
        seed(3, "100% Cotton Shirt")
        seed(4, "Mouse.Pad (large) [blue]")
        // the classic catastrophic-backtracking victim for (a+)+$
        seed(5, "a".repeat(40) + "!")
        seed(6, "price \$gt 5")
    }

    private val operatorPayloads = listOf(
        "{\"\$ne\": null}", "{\"\$gt\": \"\"}", "\$where: sleep(5000)", "'; return true; var x='", "\$regex",
        "{\"\$regex\": \".*\"}", "\"; sleep(5000); \"", ".*", ".+", "^", "\$", "^.*\$", "[a-z]+", "(?i)mouse", "\\E.*\\Q", "\\E.*",
        "(a+)+\$", "(a|aa)+\$", "(.*a){30}", "a*a*a*a*a*a*a*b", "[", "(", "*", "?", "\\"
    )

    @Test
    fun `search treats operator and regex payloads as literal text and never fails`() {
        for (payload in operatorPayloads) {
            val response = search(payload)
            assertEquals(200, response.status, "q=$payload -> ${response.body}")
            val hits = Iterable { response.json["content"].iterator() }.map { it["productName"].asString() }
            assertTrue(hits.size < 6, "q=$payload returned the whole catalog: $hits")
        }
        assertEquals(6, repository.count())
    }

    @Test
    fun `wildcard payloads match nothing instead of everything`() {
        for (payload in listOf(".*", ".+", "^", "^.*\$", "[a-z]+", "\\E.*", "\\E.*\\Q")) {
            val response = search(payload)
            assertEquals(0, response.json["totalElements"].asInt(), "q=$payload matched ${response.body}")
        }
    }

    @Test
    fun `regex metacharacters in a query are matched literally`() {
        assertEquals(listOf("Mouse.Pad (large) [blue]"), search(".Pad (large) [").json["content"].map { it["productName"].asString() })
        assertEquals(listOf("100% Cotton Shirt"), search("100% cotton").json["content"].map { it["productName"].asString() })
    }

    @Test
    fun `catastrophic regex input is answered quickly (no ReDoS)`() {
        val evil = listOf("(a+)+\$", "(a|aa)+\$", "(a*)*b", "^(([a-z])+.)+[A-Z]([a-z])+\$", "(.*a){30}")
        for (payload in evil) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), "ReDoS payload $payload") {
                assertEquals(200, search(payload).status)
            }
        }
        // also with the victim string itself as the query (fallback path scans every name)
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertEquals(200, search("a".repeat(40) + "!" + "(a+)+\$").status)
        }
    }

    @Test
    fun `text-search operators do not turn search into an enumeration oracle or an error`() {
        // \$text syntax: -term excludes, "phrase" matches phrases. Both are treated as plain query text.
        for (q in listOf("-mouse", "\"wireless mouse\"", "\"", "mouse -wireless", "\"\"", "-", "--")) {
            val response = search(q)
            assertEquals(200, response.status, "q=$q -> ${response.body}")
        }
    }

    @Test
    fun `operator-style query parameters change nothing`() {
        // bracket syntax is not interpreted by Spring MVC; the parameter is just an unknown name
        val withExtra = call("GET", "/api/v1/catalog/search?q=mouse&q%5B\$ne%5D=zzz&name%5B\$ne%5D=&productName%5B\$regex%5D=.*")
        assertEquals(200, withExtra.status, withExtra.body)
        assertEquals(search("mouse").json["totalElements"].asInt(), withExtra.json["totalElements"].asInt())

        val noQ = call("GET", "/api/v1/catalog/search?q%5B\$ne%5D=zzz")
        assertEquals(400, noQ.status, noQ.body)

        for (path in listOf(
            "/api/v1/catalog?page%5B\$gt%5D=0", "/api/v1/catalog?featured%5B\$ne%5D=false", "/api/v1/catalog?productName%5B\$regex%5D=.*&size=20"
        )) {
            val r = call("GET", path)
            assertEquals(200, r.status, "$path -> ${r.body}")
            assertEquals(6, r.json["totalElements"].asInt(), path)
        }
    }

    @ParameterizedTest(name = "path variable \"{0}\" is a 4xx")
    @ValueSource(strings = ["%7B%22%24ne%22%3Anull%7D", "%24ne", "1%20OR%201%3D1", "1.5", "abc", "-", "99999999999999999999", "%24where"])
    fun `operators in numeric path variables are rejected as client errors`(id: String) {
        for (path in listOf("/api/v1/catalog/category/$id", "/api/v1/catalog/product/$id")) {
            val r = call("GET", path)
            assertTrue(r.status in 400..499, "GET $path -> ${r.status} ${r.body}")
        }
        val sync = call("POST", "/api/v1/catalog/sync/$id")
        assertTrue(sync.status in 400..499, "sync $id -> ${sync.status} ${sync.body}")
        assertEquals(6, repository.count())
    }

    @Test
    fun `operator objects in PATCH bodies are rejected and the document is untouched`() {
        val before = repository.findById(1L).get()
        val bodies = listOf(
            """{"featured":{"${'$'}ne":false}}""",
            """{"imageUrl":{"${'$'}gt":""}}""",
            """{"price":{"${'$'}gt":0}}""",
            """{"imageUrl":["a","b"]}""",
            """{"featured":"${'$'}where"}"""
        )
        for (body in bodies) {
            val r = call("PATCH", "/api/v1/catalog/product/1", body)
            assertEquals(400, r.status, "$body -> ${r.body}")
        }
        val after = repository.findById(1L).get()
        assertEquals(before.featured, after.featured)
        assertEquals(before.imageUrl, after.imageUrl)
        assertEquals(0, before.price.compareTo(after.price))
        assertEquals(before.version, after.version, "a rejected body must not write")
    }

    @Test
    fun `update operators and dotted or dollar keys as top-level JSON keys are ignored, not executed`() {
        val before = repository.findById(1L).get()
        val body = """{"${'$'}set":{"featured":true},"${'$'}where":"sleep(5000)","a.b":1,"${'$'}unset":{"productName":1},"featured.x":true,"productName.${'$'}":"x"}"""

        val r = call("PATCH", "/api/v1/catalog/product/1", body)

        assertTrue(r.status in listOf(200, 400), "${r.status} ${r.body}")
        val after = repository.findById(1L).get()
        assertEquals("Wireless Mouse", after.productName)
        assertFalse(after.featured)
        assertEquals(before.imageUrl, after.imageUrl)
        assertEquals(6, repository.count())
    }

    @Test
    fun `dollar and dot strings are stored as plain values and never interpreted`() {
        val r = call("PATCH", "/api/v1/catalog/product/1", """{"imageUrl":"${'$'}where.${'$'}ne","price":12.5}""")
        assertEquals(200, r.status, r.body)

        assertEquals("\$where.\$ne", repository.findById(1L).get().imageUrl)
        assertEquals(6, repository.count())
    }

    @Test
    fun `NoSQL payloads in sync-ed product data are stored as literals`() {
        stubProduct(77, name = "{\\\"\$ne\\\":null}", brand = "\$where")
        stubCategories()

        val r = call("POST", "/api/v1/catalog/sync/77")

        assertEquals(200, r.status, r.body)
        assertEquals("{\"\$ne\":null}", repository.findById(77L).get().productName)
        assertEquals(7, repository.count())
    }

    @Disabled("BUG S-54: a NUL character in the search text makes the search query fail and the API answers 500 instead of 400")
    @Test
    fun `NUL character in a search query is a client error`() {
        val r = call("GET", "/api/v1/catalog/search?q=a%00b")
        assertTrue(r.status < 500, "q=a<NUL>b -> ${r.status} ${r.body}")
    }

    @Disabled("BUG S-53: no upper bound on the page size - size=100000000 returns the whole collection in one response")
    @Test
    fun `huge page size is capped or rejected`() {
        repository.saveAll((1000L..3500L).map {
            CatalogEntry(productId = it, productSku = "BULK-$it", productName = "Bulk $it", price = BigDecimal("1.00"))
        })

        val browse = call("GET", "/api/v1/catalog?size=100000000")
        assertTrue(browse.status == 400 || browse.json["content"].size() <= 2000, "browse returned ${browse.json["content"]?.size()} entries")

        val search = call("GET", "/api/v1/catalog/search?q=Bulk&size=2147483647")
        assertTrue(search.status == 400 || search.json["content"].size() <= 2000, "search returned ${search.json["content"]?.size()} entries")
    }

    @ParameterizedTest(name = "paging {0} is a client error or a safe default, never a 500")
    @ValueSource(strings = ["page=-1", "size=-1", "size=0", "page=abc", "size=1.5", "page=99999999999999999999", "page=2147483647&size=2000", "page=2147483647&size=2147483647"])
    fun `garbage paging parameters never cause server errors`(query: String) {
        for (path in listOf("/api/v1/catalog?$query", "/api/v1/catalog/search?q=mouse&$query")) {
            val r = call("GET", path)
            assertTrue(r.status < 500, "GET $path -> ${r.status} ${r.body}")
        }
    }
}
