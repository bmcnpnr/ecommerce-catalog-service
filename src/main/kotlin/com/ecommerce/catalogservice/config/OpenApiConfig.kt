package com.ecommerce.catalogservice.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(Info().title("Catalog Service API").version("1.0.0").description("Product catalog browse and search"))
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
            )
        )
}
