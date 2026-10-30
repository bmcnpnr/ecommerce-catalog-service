package com.ecommerce.catalogservice.integration

import com.ecommerce.catalogservice.config.CatalogCollectionInitializer
import com.ecommerce.catalogservice.model.CatalogEntry
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.data.mongodb.core.MongoTemplate
import java.math.BigDecimal
import java.util.Date

/**
 * The startup job against a real mongod, as the running application executes it.
 */
class CatalogCollectionInitializerIT : AbstractCatalogIT() {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var initializer: CatalogCollectionInitializer

    private val expectedIndexes = setOf(
        "_id_",
        "idx_catalog_entries_category_id",
        "idx_catalog_entries_featured",
        "idx_catalog_entries_stock_status",
        CatalogCollectionInitializer.TEXT_INDEX
    )

    private fun indexNames() = mongoTemplate.indexOps(CatalogEntry::class.java).indexInfo.map { it.name }.toSet()

    @Test
    fun `application startup has already created every index`() {
        assertEquals(expectedIndexes, indexNames())
    }

    @Test
    fun `on a fresh database it creates the collection and its indexes, and a second run changes nothing`() {
        try {
            mongoTemplate.dropCollection(CatalogEntry.COLLECTION)
            assertFalse(mongoTemplate.collectionExists(CatalogEntry.COLLECTION))

            initializer.run(DefaultApplicationArguments())

            assertTrue(mongoTemplate.collectionExists(CatalogEntry.COLLECTION))
            assertEquals(expectedIndexes, indexNames())
            val text = mongoTemplate.getCollection(CatalogEntry.COLLECTION).listIndexes()
                .first { it.getString("name") == CatalogCollectionInitializer.TEXT_INDEX }
            val weights = (text["weights"] as Document).mapValues { (it.value as Number).toDouble() }
            assertEquals(mapOf("productName" to 10.0, "brand" to 5.0, "description" to 1.0), weights)
            assertEquals("english", text.getString("default_language"))

            assertDoesNotThrow { initializer.run(DefaultApplicationArguments()) }
            assertEquals(expectedIndexes, indexNames())
        } finally {
            // Leave the shared context's database as startup left it, whatever happened above.
            initializer.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `a legacy document is backfilled and can then be updated through the API`() {
        // Written by a build before @Version and before the type hint was switched off.
        mongoTemplate.getCollection(CatalogEntry.COLLECTION).insertOne(
            Document(
                mapOf(
                    "_id" to 61L, "productSku" to "SKU-61", "productName" to "Legacy", "price" to Decimal128(BigDecimal("9.99")),
                    "featured" to false, "stockStatus" to "AVAILABLE", "createdAt" to Date(), "updatedAt" to Date(),
                    "_class" to "com.ecommerce.catalogservice.model.CatalogEntry"
                )
            )
        )

        initializer.run(DefaultApplicationArguments())

        val raw = mongoTemplate.getCollection(CatalogEntry.COLLECTION).find(Document("_id", 61L)).first()!!
        assertEquals(0L, raw["version"])
        assertFalse(raw.containsKey("_class"))

        val response = call("PATCH", "/api/v1/catalog/product/61", """{"featured":true}""")
        assertEquals(200, response.status, response.body)
        assertTrue(response.json["featured"].asBoolean())
        assertEquals(1L, repository.findById(61L).get().version)
    }

    @Test
    fun `an index that conflicts with an existing one is skipped with an error - startup and the other indexes are not affected`() {
        val collection = mongoTemplate.getCollection(CatalogEntry.COLLECTION)
        val conflicting = "idx_catalog_entries_featured"
        try {
            // same name, different key: MongoDB refuses to create the declared index until someone drops this one
            collection.dropIndex(conflicting)
            collection.createIndex(Document("brand", 1), com.mongodb.client.model.IndexOptions().name(conflicting))
            collection.dropIndex("idx_catalog_entries_stock_status")

            assertDoesNotThrow { initializer.run(DefaultApplicationArguments()) }

            assertTrue("idx_catalog_entries_stock_status" in indexNames(), "the other indexes are still created")
            assertTrue(CatalogCollectionInitializer.TEXT_INDEX in indexNames())
        } finally {
            collection.dropIndex(conflicting)
            initializer.run(DefaultApplicationArguments()) // leave the shared database as startup left it
        }
        assertEquals(expectedIndexes, indexNames())
    }
}
