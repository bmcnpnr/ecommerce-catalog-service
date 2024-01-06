package com.ecommerce.catalog.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
class CatalogServiceTest {
    private val myService = CatalogService()

    @Test
    fun `test get hello message method`() {
        // when
        val result = myService.getHelloMessage()

        // then
        assertEquals(result, HELLO_FROM_CATALOG_SERVICE)
    }
}
