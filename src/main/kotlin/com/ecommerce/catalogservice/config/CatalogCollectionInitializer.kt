package com.ecommerce.catalogservice.config

import com.ecommerce.catalogservice.exception.DependencyFailures
import com.ecommerce.catalogservice.logging.LogThrottle
import com.ecommerce.catalogservice.model.CatalogEntry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.data.mongodb.core.index.TextIndexDefinition
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Brings the `catalog_entries` collection to the shape this build expects at
 * startup — the job the Flyway migration did for the relational schema:
 *
 *  1. a one-off, idempotent backfill of documents written by earlier builds
 *     (`version` for optimistic locking, removal of the `_class` type hint);
 *  2. the secondary indexes (categoryId, featured, stockStatus) and the
 *     relevance text index used by search.
 *
 * Explicit rather than `spring.data.mongodb.auto-index-creation`, so the index
 * set is visible in one place, named, and created once the application is up
 * instead of as a side effect of mapping-context bootstrap. `createIndex` is
 * idempotent (MongoDB is a no-op on an identical existing definition). `_id`
 * (the product id) is indexed and unique by construction.
 *
 * Runs as an ApplicationRunner, i.e. before `ApplicationReadyEvent`. It must never be what keeps the
 * service down, though: a MongoDB that is slow or unreachable at that moment used to throw out of here and
 * crash-loop the container (a stack trace per restart, the pod never ready) even though browsing and syncing
 * would work the moment the database answers. Now every step is independent:
 *
 *  - the server does not answer: the step stays pending, the service starts, and the pending steps are retried
 *    in the background with a back-off from 1 s to 30 s until they succeed. Until MongoDB is back every request
 *    that needs it answers 503. One WARN line when it first fails, at most one more per minute, one INFO when
 *    it has caught up;
 *  - the step itself is refused (an index whose definition conflicts with an existing one of the same name,
 *    which needs a manual drop): one ERROR with the reason, the step is dropped and the others still run.
 *    Searching by text then fails until the index is fixed; nothing else does.
 */
@Component
class CatalogCollectionInitializer internal constructor(
    private val mongoTemplate: MongoTemplate,
    private val scheduler: RetryScheduler
) : ApplicationRunner, DisposableBean {

    @Autowired
    constructor(mongoTemplate: MongoTemplate) : this(mongoTemplate, ExecutorRetryScheduler())

    private val log = LoggerFactory.getLogger(CatalogCollectionInitializer::class.java)
    private val retryLog = LogThrottle(60, TimeUnit.SECONDS)

    /** Steps still to do, in order. Touched only while holding the monitor. */
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var attempts = 0
    private var delay = INITIAL_RETRY_DELAY

    override fun run(args: ApplicationArguments) {
        synchronized(this) {
            attempts = 0
            delay = INITIAL_RETRY_DELAY
            pending["backfill version"] = ::backfillVersion
            pending["remove _class"] = ::removeTypeHint
            INDEXES.forEach { index -> pending["index ${index.indexOptions.getString("name")}"] = { ensureIndex(index) } }
        }
        attempt()
    }

    override fun destroy() = scheduler.close()

    @Synchronized
    private fun attempt() {
        attempts++
        var unavailable: Throwable? = null
        val steps = pending.iterator()
        while (steps.hasNext()) {
            val (name, action) = steps.next()
            try {
                action()
                steps.remove()
            } catch (e: Exception) {
                unavailable = DependencyFailures.unavailableCause(e)
                if (unavailable != null) break // the server is not answering: the other steps would only wait too
                log.error("Skipping '{}' on {}: it was refused, and retrying cannot help ({})", name, CatalogEntry.COLLECTION, e.message, e)
                steps.remove()
            }
        }

        if (unavailable != null) {
            retryLater(unavailable)
        } else if (attempts > 1) {
            log.info("{} initialised after {} attempts", CatalogEntry.COLLECTION, attempts)
        }
    }

    private fun retryLater(cause: Throwable) {
        val suppressed = retryLog.acquire("initialisation")
        if (suppressed >= 0) {
            log.warn(
                "Could not initialise {} ({} step(s) pending): {}. The service starts anyway and retries in {} s{}",
                CatalogEntry.COLLECTION, pending.size, describe(cause), delay.seconds, LogThrottle.suppressedSuffix(suppressed)
            )
        }
        scheduler.schedule(delay) { attempt() }
        delay = minOf(delay.multipliedBy(2), MAX_RETRY_DELAY)
    }

    /** Class and first line of the message, capped, without control characters. */
    private fun describe(cause: Throwable): String {
        val firstLine = (cause.message ?: "").lineSequence().first().filter { !it.isISOControl() }.take(200)
        return cause.javaClass.simpleName + if (firstLine.isEmpty()) "" else ": $firstLine"
    }

    /**
     * Documents saved before `@Version` existed have no `version` field, and
     * Spring Data reads a missing version as "new" — a later save would try to
     * *insert* them and fail on the duplicate `_id`. Seeding 0 makes them
     * ordinary versioned documents.
     *
     * Both backfills address the collection by *name*, not by entity class: the
     * entity-typed overloads know about `@Version` and add `$inc: {version: 1}`
     * to any update that does not set it, which would bump every legacy
     * document on the `_class` removal.
     */
    private fun backfillVersion() {
        val versioned = mongoTemplate.updateMulti(
            Query(where("version").exists(false)),
            Update().set("version", 0L),
            CatalogEntry.COLLECTION
        ).modifiedCount
        if (versioned > 0) log.info("Backfilled {}: version set on {} document(s)", CatalogEntry.COLLECTION, versioned)
    }

    /** `_class` was written by the default type mapper before MongoConfig switched it off; it is dead weight now. */
    private fun removeTypeHint() {
        val untyped = mongoTemplate.updateMulti(
            Query(where("_class").exists(true)),
            Update().unset("_class"),
            CatalogEntry.COLLECTION
        ).modifiedCount
        if (untyped > 0) log.info("Backfilled {}: _class removed from {} document(s)", CatalogEntry.COLLECTION, untyped)
    }

    private fun ensureIndex(index: IndexDefinition) {
        val name = mongoTemplate.indexOps(CatalogEntry::class.java).createIndex(index)
        log.info("Ensured index {} on {}", name, CatalogEntry.COLLECTION)
    }

    companion object {
        const val TEXT_INDEX = "idx_catalog_entries_text"

        internal val INITIAL_RETRY_DELAY: Duration = Duration.ofSeconds(1)
        internal val MAX_RETRY_DELAY: Duration = Duration.ofSeconds(30)

        val INDEXES: List<IndexDefinition> = listOf(
            Index().on("categoryId", Sort.Direction.ASC).named("idx_catalog_entries_category_id"),
            Index().on("featured", Sort.Direction.ASC).named("idx_catalog_entries_featured"),
            Index().on("stockStatus", Sort.Direction.ASC).named("idx_catalog_entries_stock_status"),
            // MongoDB allows one text index per collection; weights make a hit
            // in the name outrank the same word in a description.
            TextIndexDefinition.builder()
                .named(TEXT_INDEX)
                .onField("productName", 10f)
                .onField("brand", 5f)
                .onField("description", 1f)
                .withDefaultLanguage("english")
                .build()
        )
    }
}

/** Runs a task after a delay; a seam so tests can run the retries by hand instead of waiting for them. */
internal interface RetryScheduler {
    fun schedule(delay: Duration, task: Runnable)
    fun close()
}

/** One daemon thread, created when the first retry is needed, so a healthy start costs nothing. */
internal class ExecutorRetryScheduler : RetryScheduler {
    private var executor: ScheduledExecutorService? = null

    @Synchronized
    override fun schedule(delay: Duration, task: Runnable) {
        val e = executor ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "catalog-collection-init").apply { isDaemon = true }
        }.also { executor = it }
        e.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        executor?.shutdownNow()
    }
}
