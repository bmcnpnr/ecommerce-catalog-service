package com.ecommerce.catalog.controller

import com.ecommerce.catalog.service.CatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CatalogController(private val catalogService: CatalogService) {
    @GetMapping("/hello")
    fun sayHello(): String {
        return catalogService.getHelloMessage()
    }
}
