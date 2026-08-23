package com.ecommerce.catalogservice.repository

import com.ecommerce.catalogservice.client.ProductServiceClient
import com.ecommerce.catalogservice.config.CatalogCollectionInitializer
import com.ecommerce.catalogservice.config.MongoConfig
import com.ecommerce.catalogservice.dto.ProductDTO
import com.ecommerce.catalogservice.service.CatalogService
import com.ecommerce.catalogservice.model.CatalogEntry
import com.ecommerce.catalogservice.model.StockStatus
import com.mongodb.client.model.Filters
import org.bson.types.Decimal128
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.bson.Document
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Runs the repository against a real mongod (the same major version the
 * platform deploys) so the derived queries, the Decimal128 price mapping and
 * the startup index creation are exercised for real rather than mocked.
 *
 * `disabledWithoutDocker = true`: on a machine with no Docker daemon the class
 * is skipped, not failed, so `mvn package` still works there. CircleCI's
 * verify job mounts the Docker socket (see CICD.md) and runs it.
 */
@DataMongoTest
@Import(CatalogCollectionInitializer::class, MongoConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class CatalogEntryRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8.0")
    }

    @Autowired
    private lateinit var repository: CatalogEntryRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun entry(
        productId: Long,
        name: String = "Product $productId",
        categoryId: Long? = 1L,
        featured: Boolean = false,
        stock: StockStatus = StockStatus.AVAILABLE,
        price: BigDecimal = BigDecimal("29.99")
    ) = CatalogEntry(
        productId = productId,
        productSku = "SKU-$productId",
        productName = name,
        description = "desc",
        price = price,
        brand = "ACME",
        categoryId = categoryId,
        categoryName = "Cat $categoryId",
        featured = featured,
        stockStatus = stock,
        imageUrl = null
    )

    @Test
    fun `round-trips every field, with the product id as the document id`() {
        val saved = repository.save(entry(100L, price = BigDecimal("1234.50")))

        val loaded = repository.findById(100L).orElseThrow()

        assertEquals(100L, loaded.productId)
        assertEquals("SKU-100", loaded.productSku)
        assertEquals("Product 100", loaded.productName)
        assertEquals("desc", loaded.description)
        assertEquals(BigDecimal("1234.50"), loaded.price, "scale is preserved through Decimal128")
        assertEquals("ACME", loaded.brand)
        assertEquals(1L, loaded.categoryId)
        assertEquals("Cat 1", loaded.categoryName)
        assertFalse(loaded.featured)
        assertEquals(StockStatus.AVAILABLE, loaded.stockStatus)
        assertNull(loaded.imageUrl)
        // BSON dates are millisecond precision; CatalogEntry.now() truncates so
        // the in-memory value and the persisted value are identical.
        assertEquals(saved.createdAt, loaded.createdAt)
        assertEquals(saved.updatedAt, loaded.updatedAt)

        val raw = mongoTemplate.execute(CatalogEntry::class.java) { collection ->
            collection.find(Filters.eq("_id", 100L)).first()
        }!!
        assertEquals(100L, raw["_id"])
        assertTrue(raw["price"] is Decimal128, "price must be stored as Decimal128, not a string: ${raw["price"]}")
        assertEquals("AVAILABLE", raw["stockStatus"])
        assertNull(raw["productId"], "the product id lives in _id only, not duplicated into a second field")
        assertNull(raw["_class"], "MongoConfig switches the type hint off")
        assertEquals(0L, raw["version"], "first save seeds the optimistic-lock version")
        assertEquals(0L, loaded.version)
    }

    @Test
    fun `a fresh entity for an existing product id is an insert and collides - the idempotent path is load-modify-save`() {
        repository.save(entry(7L, name = "first"))

        // version == null means "new" to Spring Data -> insert -> E11000 on _id.
        // CatalogService.syncFromProductService catches exactly this and retries
        // with a load-modify-save; at the repository level it must surface.
        assertThrows<org.springframework.dao.DuplicateKeyException> { repository.save(entry(7L, name = "second")) }

        val loaded = repository.findById(7L).orElseThrow()
        loaded.productName = "second"
        repository.save(loaded)
        assertEquals(1, repository.count())
        assertEquals("second", repository.findById(7L).orElseThrow().productName)
        assertEquals(1L, repository.findById(7L).orElseThrow().version)
    }

    @Test
    fun `findByCategoryId and findByFeaturedTrue filter correctly`() {
        repository.saveAll(
            listOf(
                entry(1L, categoryId = 10L, featured = true),
                entry(2L, categoryId = 10L),
                entry(3L, categoryId = 20L, featured = true),
                entry(4L, categoryId = null)
            )
        )

        assertEquals(setOf(1L, 2L), repository.findByCategoryId(10L).map { it.productId }.toSet())
        assertEquals(setOf(1L, 3L), repository.findByFeaturedTrue().map { it.productId }.toSet())
        assertEquals(emptyList<CatalogEntry>(), repository.findByCategoryId(99L))
    }

    @Test
    fun `findByStockStatus filters on the enum stored as a string`() {
        repository.saveAll(listOf(entry(1L, stock = StockStatus.OUT_OF_STOCK), entry(2L, stock = StockStatus.LOW_STOCK)))

        assertEquals(listOf(1L), repository.findByStockStatus(StockStatus.OUT_OF_STOCK).map { it.productId })
    }

    @Test
    fun `name search is a case-insensitive substring match and is paged`() {
        repository.saveAll(
            listOf(
                entry(1L, name = "Wireless Mouse"),
                entry(2L, name = "wireless keyboard"),
                entry(3L, name = "USB Cable"),
                entry(4L, name = "WIRELESS headset")
            )
        )

        val page0 = repository.findByProductNameContainingIgnoreCase("wIrElEsS", PageRequest.of(0, 2))
        assertEquals(3, page0.totalElements)
        assertEquals(2, page0.content.size)
        assertEquals(2, page0.totalPages)

        val page1 = repository.findByProductNameContainingIgnoreCase("wIrElEsS", PageRequest.of(1, 2))
        assertEquals(1, page1.content.size)
        assertEquals(3, (page0.content + page1.content).map { it.productId }.toSet().size)
    }

    @Test
    fun `name search treats regex metacharacters as literal text`() {
        repository.saveAll(listOf(entry(1L, name = "C++ (Deluxe) [2nd ed.]"), entry(2L, name = "C")))

        // Unquoted, "(Deluxe)" would be a regex group and "C++" a syntax error.
        assertEquals(listOf(1L), repository.findByProductNameContainingIgnoreCase("(deluxe)", PageRequest.of(0, 10)).content.map { it.productId })
        assertEquals(listOf(1L), repository.findByProductNameContainingIgnoreCase("c++", PageRequest.of(0, 10)).content.map { it.productId })
        assertEquals(listOf(1L), repository.findByProductNameContainingIgnoreCase("[2nd ed.]", PageRequest.of(0, 10)).content.map { it.productId })
        assertEquals(0, repository.findByProductNameContainingIgnoreCase(".*", PageRequest.of(0, 10)).totalElements)
    }

    @Test
    fun `findAll is paged`() {
        repository.saveAll((1L..5L).map { entry(it) })

        val page = repository.findAll(PageRequest.of(1, 2))
        assertEquals(5, page.totalElements)
        assertEquals(2, page.content.size)
    }

    @Test
    fun `startup creates the secondary indexes by name`() {
        val names = mongoTemplate.indexOps(CatalogEntry::class.java).indexInfo.map { it.name }.toSet()

        val expected = CatalogCollectionInitializer.INDEXES.map { it.indexOptions["name"] as String }
        assertEquals(4, expected.size)
        assertTrue(CatalogCollectionInitializer.TEXT_INDEX in expected)
        assertTrue(names.containsAll(expected), "expected $expected in $names")
        assertTrue("_id_" in names)
    }

    @Test
    fun `timestamps survive a load-modify-save cycle`() {
        val created = repository.save(entry(1L))
        val before = created.createdAt

        val loaded = repository.findById(1L).orElseThrow()
        loaded.featured = true
        loaded.updatedAt = CatalogEntry.now()
        repository.save(loaded)

        val reloaded = repository.findById(1L).orElseThrow()
        assertEquals(before, reloaded.createdAt, "createdAt is immutable across updates")
        assertTrue(reloaded.featured)
        assertFalse(reloaded.updatedAt.isBefore(before))
        assertTrue(reloaded.updatedAt.isBefore(LocalDateTime.now().plusSeconds(1)))
    }

    @Test
    fun `text search ranks name hits above description hits and matches any term`() {
        repository.saveAll(
            listOf(
                entry(1L, name = "Wireless Mouse"),
                entry(2L, name = "Mechanical Keyboard").also { it.description = "USB, wireless dongle included" },
                entry(3L, name = "USB-C Cable"),
                entry(4L, name = "Bluetooth Headset").also { it.brand = "Wireless Co" }
            )
        )

        val page = repository.searchByText("wireless", PageRequest.of(0, 10))

        assertEquals(3, page.totalElements)
        assertEquals(1L, page.content.first().productId, "name (weight 10) outranks brand (5) and description (1)")
        assertEquals(setOf(1L, 2L, 4L), page.content.map { it.productId }.toSet())

        // Any term may match (OR): "mouse keyboard" returns both, stemmed/case-insensitive.
        assertEquals(setOf(1L, 2L), repository.searchByText("MOUSE keyboards", PageRequest.of(0, 10)).content.map { it.productId }.toSet())

        // Whole words only: a partial word is not a text hit (the service falls back to substring for this).
        assertEquals(0, repository.searchByText("wirel", PageRequest.of(0, 10)).totalElements)
    }

    @Test
    fun `text search is paged with a stable total`() {
        repository.saveAll((1L..5L).map { entry(it, name = "Gadget $it") })

        val page0 = repository.searchByText("gadget", PageRequest.of(0, 2))
        val page2 = repository.searchByText("gadget", PageRequest.of(2, 2))
        assertEquals(5, page0.totalElements)
        assertEquals(2, page0.content.size)
        assertEquals(1, page2.content.size)
        assertEquals(3, page0.totalPages)
    }

    @Test
    fun `service search uses the text index and falls back to substring for partial words`() {
        repository.saveAll(listOf(entry(1L, name = "Wireless Mouse"), entry(2L, name = "Wired Mouse")))
        val service = CatalogService(repository, Mockito.mock(ProductServiceClient::class.java))

        assertEquals(listOf(1L), service.searchCatalog("wireless", PageRequest.of(0, 10)).content.map { it.productId })
        assertEquals(setOf(1L, 2L), service.searchCatalog("mouse", PageRequest.of(0, 10)).content.map { it.productId }.toSet())
        // "wirel" is not a word -> text index empty -> substring fallback finds "Wireless"
        assertEquals(listOf(1L), service.searchCatalog("wirel", PageRequest.of(0, 10)).content.map { it.productId })
        // blank -> substring path -> everything
        assertEquals(2, service.searchCatalog(" ", PageRequest.of(0, 10)).totalElements)
    }

    @Test
    fun `a stale save is rejected by the version check`() {
        repository.save(entry(1L))
        val first = repository.findById(1L).orElseThrow()
        val second = repository.findById(1L).orElseThrow()

        first.featured = true
        repository.save(first)
        assertEquals(1L, repository.findById(1L).orElseThrow().version)

        second.imageUrl = "http://img/late.png"
        assertThrows<OptimisticLockingFailureException> { repository.save(second) }
        val stored = repository.findById(1L).orElseThrow()
        assertTrue(stored.featured, "the first writer's change stands")
        assertNull(stored.imageUrl, "the stale writer's change was rejected, not merged")
    }

    @Test
    fun `service sync converges when two first-time syncs race on the same product`() {
        val client = Mockito.mock(ProductServiceClient::class.java)
        Mockito.`when`(client.getProductById(42L)).thenReturn(
            ProductDTO(id = 42L, sku = "SKU-42", name = "Raced Widget", description = null,
                price = BigDecimal("5.00"), stockQuantity = 1, brand = null, categoryId = null, status = "ACTIVE")
        )
        Mockito.`when`(client.getAllCategories()).thenReturn(emptyList())

        // The loser of the race: its findById sees nothing, then the winner's
        // document lands before its insert. Reproduced deterministically by a
        // delegating repository whose first findById answers "empty" while the
        // winner's document is already in the collection — the insert then
        // really collides on _id in mongod, and the retry must update in place.
        repository.save(entry(42L, name = "winner"))
        var firstLookup = true
        val racing = object : CatalogEntryRepository by repository {
            override fun findById(id: Long): java.util.Optional<CatalogEntry> {
                if (firstLookup) { firstLookup = false; return java.util.Optional.empty() }
                return repository.findById(id)
            }
        }
        val service = CatalogService(racing, client)

        val dto = service.syncFromProductService(42L)

        assertEquals("Raced Widget", dto.productName)
        assertEquals(1, repository.count())
        assertEquals(1L, repository.findById(42L).orElseThrow().version, "updated in place after the lost insert; version advanced once")
    }

    @Test
    fun `startup backfills version and strips _class from documents written by earlier builds`() {
        // A document exactly as the first Mongo build of this service wrote it.
        mongoTemplate.getCollection(CatalogEntry.COLLECTION).insertOne(
            Document(mapOf(
                "_id" to 77L, "productSku" to "SKU-77", "productName" to "Legacy", "price" to org.bson.types.Decimal128(BigDecimal("1.00")),
                "featured" to false, "stockStatus" to "AVAILABLE",
                "createdAt" to java.util.Date(), "updatedAt" to java.util.Date(),
                "_class" to "com.ecommerce.catalogservice.model.CatalogEntry"
            ))
        )

        CatalogCollectionInitializer(mongoTemplate).run(org.springframework.boot.DefaultApplicationArguments())

        val raw = mongoTemplate.getCollection(CatalogEntry.COLLECTION).find(Filters.eq("_id", 77L)).first()!!
        assertEquals(0L, raw["version"])
        assertNull(raw["_class"])

        // and it is now an ordinary versioned document: load-modify-save works
        val legacy = repository.findById(77L).orElseThrow()
        legacy.featured = true
        repository.save(legacy)
        assertEquals(1L, repository.findById(77L).orElseThrow().version)
    }
}
