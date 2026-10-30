package com.ecommerce.catalogservice.config

import com.ecommerce.catalogservice.model.CatalogEntry
import com.mongodb.client.result.UpdateResult
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.Duration
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
import org.springframework.dao.InvalidDataAccessApiUsageException
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

    // ───────────────────────── the database does not answer, or refuses a step ─────────────────────────

    /** Runs the retries by hand instead of waiting for them. */
    private class FakeScheduler : RetryScheduler {
        val tasks = ArrayDeque<Pair<Duration, Runnable>>()
        var closed = false
        override fun schedule(delay: Duration, task: Runnable) {
            tasks.addLast(delay to task)
        }
        override fun close() {
            closed = true
        }
        fun runNext(): Duration {
            val (delay, task) = tasks.removeFirst()
            task.run()
            return delay
        }
    }

    private val logs = ListAppender<ILoggingEvent>()
    private val initializerLogger = LoggerFactory.getLogger(CatalogCollectionInitializer::class.java) as Logger

    @BeforeEach
    fun captureLog() {
        logs.start()
        initializerLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLog() {
        initializerLogger.detachAppender(logs)
        logs.stop()
    }

    private fun timeout() = DataAccessResourceFailureException("Timed out while waiting for a server that matches ReadPreference primary")

    private fun initializer(scheduler: FakeScheduler) = CatalogCollectionInitializer(mongoTemplate, scheduler)

    @Test
    fun `a database that does not answer does not fail startup - nothing else is attempted and a retry is scheduled`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())).thenThrow(timeout())
        val scheduler = FakeScheduler()

        assertDoesNotThrow { initializer(scheduler).run(DefaultApplicationArguments()) }

        verify(mongoTemplate, never()).indexOps(CatalogEntry::class.java)
        assertEquals(listOf(Duration.ofSeconds(1)), scheduler.tasks.map { it.first })
    }

    @Test
    fun `the retries back off from 1 s to 30 s and stay there`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())).thenThrow(timeout())
        val scheduler = FakeScheduler()
        initializer(scheduler).run(DefaultApplicationArguments())

        val delays = (1..8).map { scheduler.runNext().seconds }

        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), delays)
        assertEquals(1, scheduler.tasks.size, "exactly one retry is pending at any time")
    }

    @Test
    fun `when the database returns the remaining steps run - in order, and none of the finished ones twice`() {
        // backfill version works, then the server goes away; it is back on the first retry
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenReturn(UpdateResult.acknowledged(0, 0, null))     // 1: version
            .thenThrow(timeout())                                  // 2: _class
            .thenReturn(UpdateResult.acknowledged(0, 0, null))     // 2 again, on the retry
        `when`(mongoTemplate.indexOps(CatalogEntry::class.java)).thenReturn(indexOps)
        `when`(indexOps.createIndex(any(IndexDefinition::class.java))).thenAnswer {
            (it.arguments[0] as IndexDefinition).indexOptions.getString("name")
        }
        val scheduler = FakeScheduler()
        initializer(scheduler).run(DefaultApplicationArguments())
        verify(indexOps, never()).createIndex(any(IndexDefinition::class.java))

        scheduler.runNext()

        verify(mongoTemplate, times(3)).updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())
        CatalogCollectionInitializer.INDEXES.forEach { verify(indexOps, times(1)).createIndex(it) }
        assertTrue(scheduler.tasks.isEmpty(), "nothing left to retry")
        assertEquals(Level.INFO, logs.list.last().level)
        assertTrue(logs.list.last().formattedMessage.contains("initialised after 2 attempts"))
    }

    @Test
    fun `a database that stays down for hours costs one warning, not one per retry, and never a stack trace`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())).thenThrow(timeout())
        val scheduler = FakeScheduler()
        initializer(scheduler).run(DefaultApplicationArguments())

        repeat(200) { scheduler.runNext() }   // 201 failed attempts

        val warnings = logs.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "the throttle allows one line per minute, and these attempts happen within one")
        assertTrue(warnings[0].formattedMessage.contains("Could not initialise catalog_entries"))
        assertTrue(logs.list.none { it.throwableProxy != null }, "no stack trace for a database that is not answering")
        assertTrue(logs.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a step the database refuses is skipped with one error - the other steps still run and nothing is retried`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenReturn(UpdateResult.acknowledged(0, 0, null))
        `when`(mongoTemplate.indexOps(CatalogEntry::class.java)).thenReturn(indexOps)
        `when`(indexOps.createIndex(any(IndexDefinition::class.java))).thenAnswer {
            val index = it.arguments[0] as IndexDefinition
            val name = index.indexOptions.getString("name")
            if (name == CatalogCollectionInitializer.TEXT_INDEX) {
                throw InvalidDataAccessApiUsageException("An existing index has the same name as the requested index")
            }
            name
        }
        val scheduler = FakeScheduler()

        assertDoesNotThrow { initializer(scheduler).run(DefaultApplicationArguments()) }

        CatalogCollectionInitializer.INDEXES.forEach { verify(indexOps).createIndex(it) } // the others were still created
        assertTrue(scheduler.tasks.isEmpty(), "a refusal cannot be cured by waiting")
        val errors = logs.list.filter { it.level == Level.ERROR }
        assertEquals(1, errors.size)
        assertTrue(errors[0].formattedMessage.contains(CatalogCollectionInitializer.TEXT_INDEX))
    }

    @Test
    fun `running it again starts from scratch and a successful run leaves nothing pending`() {
        `when`(mongoTemplate.updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString()))
            .thenReturn(UpdateResult.acknowledged(0, 0, null))
        `when`(mongoTemplate.indexOps(CatalogEntry::class.java)).thenReturn(indexOps)
        `when`(indexOps.createIndex(any(IndexDefinition::class.java))).thenAnswer {
            (it.arguments[0] as IndexDefinition).indexOptions.getString("name")
        }
        val scheduler = FakeScheduler()
        val initializer = initializer(scheduler)

        initializer.run(DefaultApplicationArguments())
        initializer.run(DefaultApplicationArguments())

        verify(mongoTemplate, times(4)).updateMulti(any(Query::class.java), any(UpdateDefinition::class.java), anyString())
        assertTrue(scheduler.tasks.isEmpty())
        assertTrue(logs.list.none { it.formattedMessage.contains("attempts") }, "a clean run is not reported as a recovery")
    }

    @Test
    fun `shutdown stops the retries`() {
        val scheduler = FakeScheduler()

        initializer(scheduler).destroy()

        assertTrue(scheduler.closed)
    }
}
