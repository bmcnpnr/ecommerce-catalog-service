package com.ecommerce.catalogservice.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * One catalog entry per product: a denormalised, read-optimised projection of
 * what product-service knows, plus the merchandising fields (featured, image)
 * that only the catalog owns.
 *
 * Stored as a MongoDB document. The product id *is* the document `_id` — a
 * product has exactly one entry, so the natural key is the primary key and
 * there can never be two entries for one product. Writes are version-checked
 * (see [version]); the service retries the idempotent sync on a concurrent
 * write and surfaces a concurrent PATCH as 409.
 *
 * Secondary and text indexes are created at startup
 * by [com.ecommerce.catalogservice.config.CatalogCollectionInitializer], which also
 * backfills [version] on documents written before optimistic locking existed.
 */
@Document(collection = CatalogEntry.COLLECTION)
class CatalogEntry(
    @Id
    val productId: Long,

    var productSku: String,

    var productName: String,

    var description: String? = null,

    /** Persisted as Decimal128 (see spring.data.mongodb.representation.big-decimal). */
    var price: BigDecimal,

    var brand: String? = null,

    var categoryId: Long? = null,

    var categoryName: String? = null,

    var featured: Boolean = false,

    var stockStatus: StockStatus = StockStatus.AVAILABLE,

    var imageUrl: String? = null,

    val createdAt: LocalDateTime = now(),

    var updatedAt: LocalDateTime = now(),

    /**
     * Optimistic lock. Spring Data increments it on every save and rejects a
     * save whose in-memory version is behind the stored one with
     * OptimisticLockingFailureException, so two concurrent PATCHes can no
     * longer silently overwrite each other (the loser gets 409). Nullable on
     * purpose: Spring Data treats a null version as "new document" and a
     * non-null one as "update with version check".
     */
    @Version
    var version: Long? = null
) {
    companion object {
        const val COLLECTION = "catalog_entries"

        /**
         * BSON dates carry millisecond precision. Truncating here keeps the
         * value we hand back straight after a save identical to the one a
         * later read returns, instead of differing in the sub-millisecond digits.
         */
        fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
    }
}
