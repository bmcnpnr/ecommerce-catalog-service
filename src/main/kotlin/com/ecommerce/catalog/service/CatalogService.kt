package com.ecommerce.catalog.service

import org.springframework.stereotype.Service

const val HELLO_FROM_CATALOG_SERVICE = "Hello from Catalog Service!"

@Service
class CatalogService {
    fun getHelloMessage(): String {
        return HELLO_FROM_CATALOG_SERVICE
    }
}
