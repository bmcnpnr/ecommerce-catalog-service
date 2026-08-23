package com.ecommerce.catalogservice.config

import com.ecommerce.catalogservice.model.CatalogEntry
import org.slf4j.LoggerFactory
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
 * Runs as an ApplicationRunner, i.e. before `ApplicationReadyEvent`, so the
 * readiness probe only passes once this has completed. If MongoDB is
 * unreachable the exception fails startup — the same fail-fast Flyway gave —
 * and compose / Kubernetes restart the container once the database is healthy.
 */
@Component
class CatalogCollectionInitializer(private val mongoTemplate: MongoTemplate) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(CatalogCollectionInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        backfillLegacyDocuments()
        ensureIndexes()
    }

    /**
     * Documents saved before `@Version` existed have no `version` field, and
     * Spring Data reads a missing version as "new" — a later save would try to
     * *insert* them and fail on the duplicate `_id`. Seeding 0 makes them
     * ordinary versioned documents. `_class` was written by the default type
     * mapper before MongoConfig switched it off; it is dead weight now.
     *
     * Both updates address the collection by *name*, not by entity class: the
     * entity-typed overloads know about `@Version` and add `$inc: {version: 1}`
     * to any update that does not set it, which would bump every legacy
     * document on the `_class` removal.
     */
    private fun backfillLegacyDocuments() {
        val versioned = mongoTemplate.updateMulti(
            Query(where("version").exists(false)),
            Update().set("version", 0L),
            CatalogEntry.COLLECTION
        ).modifiedCount
        val untyped = mongoTemplate.updateMulti(
            Query(where("_class").exists(true)),
            Update().unset("_class"),
            CatalogEntry.COLLECTION
        ).modifiedCount
        if (versioned > 0 || untyped > 0) {
            log.info("Backfilled {}: version on {} document(s), _class removed from {}", CatalogEntry.COLLECTION, versioned, untyped)
        }
    }

    private fun ensureIndexes() {
        val indexOps = mongoTemplate.indexOps(CatalogEntry::class.java)
        INDEXES.forEach { index ->
            val name = indexOps.createIndex(index)
            log.info("Ensured index {} on {}", name, CatalogEntry.COLLECTION)
        }
    }

    companion object {
        const val TEXT_INDEX = "idx_catalog_entries_text"

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
