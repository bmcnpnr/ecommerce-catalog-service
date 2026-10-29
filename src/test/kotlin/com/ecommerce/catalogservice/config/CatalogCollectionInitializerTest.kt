package com.ecommerce.catalogservice.config

import com.ecommerce.catalogservice.model.CatalogEntry
import com.mongodb.client.result.UpdateResult
import org.bson.Document
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.data.mongodb.core.index.IndexOperations
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.UpdateDefinition

/**
 * The startup job with MongoDB mocked out: what it asks the database to do and in
 * which order. CatalogEntryRepositoryTest and CatalogCollectionInitializerIT run the
 * same job against a real mongod.
 */
@ExtendWith(MockitoExtension::class)
class CatalogCollectionInitializerTest {

    @Mock
    private lateinit var mongoTemplate: MongoTemplate

    @Mock
    private lateinit var indexOps: IndexOperations

    private lateinit var initializer: CatalogCollectionInitializer

    @BeforeEach
    fun setUp() {
        initializer = CatalogCollectionInitializer(mongoTemplate)
    }

    private fun stubHappyPath(versioned: Long = 0, untyped: Long = 0) {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenReturn(UpdateResult.acknowledged(versioned, versioned, null))
            .thenReturn(UpdateResult.acknowledged(untyped, untyped, null))
        `when`(mongoTemplate.indexOps(CatalogEntry::class.java)).thenReturn(indexOps)
        `when`(indexOps.createIndex(any(IndexDefinition::class.java))).thenAnswer {
            (it.arguments[0] as IndexDefinition).indexOptions.getString("name")
        }
    }

    private fun run() = initializer.run(DefaultApplicationArguments())

    @Test
    fun `backfills a missing version, then removes the type hint - both addressed by collection name`() {
        stubHappyPath(versioned = 3, untyped = 2)

        run()

        val queries = ArgumentCaptor.forClass(Query::class.java)
        val updates = ArgumentCaptor.forClass(UpdateDefinition::class.java)
        verify(mongoTemplate, times(2)).updateMulti(queries.capture(), updates.capture(), eq(CatalogEntry.COLLECTION))

        assertEquals(Document("version", Document("\$exists", false)), queries.allValues[0].queryObject)
        assertEquals(Document("\$set", Document("version", 0L)), updates.allValues[0].updateObject)
        assertEquals(Document("_class", Document("\$exists", true)), queries.allValues[1].queryObject)
        assertEquals(Document("\$unset", Document("_class", 1)), updates.allValues[1].updateObject)
    }

    @Test
    fun `never uses the entity-typed update, which would bump the version of every legacy document`() {
        stubHappyPath()

        run()

        verify(mongoTemplate, never()).updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), eq(CatalogEntry::class.java))
    }

    @Test
    fun `creates every declared index, in order, after the backfill`() {
        stubHappyPath()

        run()

        val order = inOrder(mongoTemplate, indexOps)
        order.verify(mongoTemplate, times(2)).updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())
        CatalogCollectionInitializer.INDEXES.forEach { order.verify(indexOps).createIndex(it) }
        verifyNoMoreInteractions(indexOps)
    }

    @Test
    fun `the declared index set - three ascending secondaries and one weighted text index`() {
        val byName = CatalogCollectionInitializer.INDEXES.associateBy { it.indexOptions.getString("name") }

        assertEquals(
            setOf(
                "idx_catalog_entries_category_id",
                "idx_catalog_entries_featured",
                "idx_catalog_entries_stock_status",
                CatalogCollectionInitializer.TEXT_INDEX
            ),
            byName.keys
        )
        assertEquals(Document("categoryId", 1), byName["idx_catalog_entries_category_id"]!!.indexKeys)
        assertEquals(Document("featured", 1), byName["idx_catalog_entries_featured"]!!.indexKeys)
        assertEquals(Document("stockStatus", 1), byName["idx_catalog_entries_stock_status"]!!.indexKeys)

        val text = byName[CatalogCollectionInitializer.TEXT_INDEX]!!
        assertEquals(setOf("productName", "brand", "description"), text.indexKeys.keys)
        assertTrue(text.indexKeys.values.all { it == "text" })
        val weights = text.indexOptions["weights"] as Document
        assertEquals(10f, (weights["productName"] as Number).toFloat())
        assertEquals(5f, (weights["brand"] as Number).toFloat())
        // Weight 1 is MongoDB's default, so Spring leaves it out of the definition.
        assertEquals(1f, (weights["description"] as Number?)?.toFloat() ?: 1f)
        assertEquals("english", text.indexOptions.getString("default_language"))
    }

    @Test
    fun `a database failure fails startup and no index is attempted`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenThrow(DataAccessResourceFailureException("Timed out connecting to catalogdb"))

        assertThrows<DataAccessResourceFailureException> { run() }
        verify(mongoTemplate, never()).indexOps(CatalogEntry::class.java)
    }

    @Test
    fun `an index failure is not swallowed either`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenReturn(UpdateResult.acknowledged(0, 0, null))
        `when`(mongoTemplate.indexOps(CatalogEntry::class.java)).thenReturn(indexOps)
        `when`(indexOps.createIndex(any(IndexDefinition::class.java)))
            .thenThrow(DataAccessResourceFailureException("Index build failed"))

        assertThrows<DataAccessResourceFailureException> { run() }
    }
}
