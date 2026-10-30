package com.ecommerce.catalogservice.repository

import com.ecommerce.catalogservice.model.CatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query

/**
 * The text search and the substring scan are the two queries that touch many documents. They carry a server-side time
 * limit, so a pathological search is aborted by MongoDB itself instead of keeping the single mongod busy after the
 * client's socket timeout has given up on it.
 */
class CatalogEntryRepositoryImplTest {

    private val mongoTemplate = mock(MongoTemplate::class.java)
    private val repository = CatalogEntryRepositoryImpl(mongoTemplate)

    @Test
    fun `the text search and its count both carry the server-side time limit`() {
        `when`(mongoTemplate.find(any(Query::class.java), any<Class<CatalogEntry>>())).thenReturn(emptyList())
        `when`(mongoTemplate.count(any(Query::class.java), any<Class<CatalogEntry>>())).thenReturn(0L)

        // an empty first page forces the count query as well
        repository.searchByText("mouse", PageRequest.of(1, 20))

        val queries = ArgumentCaptor.forClass(Query::class.java)
        verify(mongoTemplate).find(queries.capture(), any<Class<CatalogEntry>>())
        assertEquals(4000L, queries.value.meta.maxTimeMsec)
        val countQuery = ArgumentCaptor.forClass(Query::class.java)
        verify(mongoTemplate).count(countQuery.capture(), any<Class<CatalogEntry>>())
        assertEquals(4000L, countQuery.value.meta.maxTimeMsec)
    }

    @Test
    fun `the substring scan carries it too`() {
        val meta = CatalogEntryRepository::class.java.getMethod(
            "findByProductNameContainingIgnoreCase", String::class.java, org.springframework.data.domain.Pageable::class.java
        ).getAnnotation(org.springframework.data.mongodb.repository.Meta::class.java)

        assertEquals(4000L, meta.maxExecutionTimeMs)
    }
}
