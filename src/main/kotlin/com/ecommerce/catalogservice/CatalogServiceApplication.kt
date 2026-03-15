package com.ecommerce.catalogservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@SpringBootApplication
@EnableFeignClients
class CatalogServiceApplication

fun main(args: Array<String>) {
    runApplication<CatalogServiceApplication>(*args)
}
