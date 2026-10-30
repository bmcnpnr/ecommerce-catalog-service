package com.ecommerce.catalogservice.repository

import com.ecommerce.catalogservice.model.CatalogEntry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.TextCriteria
import org.springframework.data.mongodb.core.query.TextQuery
import org.springframework.data.support.PageableExecutionUtils
import java.time.Duration

/**
 * Full-text search over the collection's text index (productName, brand,
 * description — see CatalogCollectionInitializer), relevance-ranked.
 */
interface CatalogEntryRepositoryCustom {
    /**
     * MongoDB `$text` search: the query is tokenised and stemmed, any term may
     * match (OR), results come best-score first, then by the pageable's own
     * sort. Whole words only — "wirel" does not match "wireless"; the service
     * falls back to a substring match for that case.
     */
    fun searchByText(text: String, pageable: Pageable): Page<CatalogEntry>

    companion object {
        /**
         * Server-side time limit (`maxTimeMS`) of the two searches that scan or rank many documents. MongoDB aborts the
         * query itself, so one pathological search cannot keep the single mongod busy after the client has given up
         * (the driver's socket timeout is 5 s and only abandons the connection). The exception it raises is
         * answered with a 503 like any other "database did not answer".
         */
        const val QUERY_TIME_LIMIT_MS = 4000L
    }
}

/** Picked up by Spring Data through the `Impl` naming convention. */
class CatalogEntryRepositoryImpl(private val mongoTemplate: MongoTemplate) : CatalogEntryRepositoryCustom {

    override fun searchByText(text: String, pageable: Pageable): Page<CatalogEntry> {
        val criteria = TextCriteria.forDefaultLanguage().matching(text)
        val query = TextQuery.queryText(criteria).sortByScore().with(pageable)
            .maxTime(Duration.ofMillis(CatalogEntryRepositoryCustom.QUERY_TIME_LIMIT_MS))
        val content = mongoTemplate.find(query, CatalogEntry::class.java)
        return PageableExecutionUtils.getPage(content, pageable) {
            mongoTemplate.count(
                Query().addCriteria(criteria).maxTime(Duration.ofMillis(CatalogEntryRepositoryCustom.QUERY_TIME_LIMIT_MS)),
                CatalogEntry::class.java
            )
        }
    }
}
