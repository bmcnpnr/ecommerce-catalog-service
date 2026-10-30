package com.ecommerce.catalogservice.security

import com.ecommerce.catalogservice.controller.CatalogController
import com.ecommerce.catalogservice.exception.GlobalExceptionHandler
import com.ecommerce.catalogservice.service.CatalogService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.stream.Stream

/**
 * The gateway validates the JWT and forwards `X-User-Role`. The catalog's write endpoints
 * (sync, PATCH of featured/imageUrl/price) are documented "(Admin)" and must refuse anyone
 * else before the service is reached. Reads stay public.
 */
@Tag("security")
class CatalogAuthorizationSecurityTest {

    private val service: CatalogService = mock(CatalogService::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(CatalogController(service))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    data class Write(val name: String, val method: HttpMethod, val path: String, val body: String?) {
        override fun toString() = name
    }

    companion object {
        private val WRITES = listOf(
            Write("sync product", HttpMethod.POST, "/api/v1/catalog/sync/1", null),
            Write("feature a product", HttpMethod.PATCH, "/api/v1/catalog/product/1", """{"featured":true}"""),
            Write("change image url", HttpMethod.PATCH, "/api/v1/catalog/product/1", """{"imageUrl":"http://evil.example/x.png"}"""),
            Write("change price", HttpMethod.PATCH, "/api/v1/catalog/product/1", """{"price":0.01}""")
        )

        /** `null` means "no X-User-Role header at all". */
        private val NON_ADMIN_ROLES = listOf(
            "CUSTOMER", null, "", "admin", "Admin", " ADMIN", "ADMIN ", "ROLE_ADMIN", "ADMINISTRATOR", "ADMIN,CUSTOMER"
        )

        @JvmStatic
        fun nonAdminCallers(): Stream<Arguments> =
            WRITES.stream().flatMap { w -> NON_ADMIN_ROLES.stream().map { r -> Arguments.of(w, r) } }

        @JvmStatic
        fun writes(): Stream<Arguments> = WRITES.stream().map { Arguments.of(it) }
    }

    private fun build(w: Write, role: String?) =
        request(w.method, w.path).header("X-Username", "mallory").header("X-User-Id", "7").apply {
            if (role != null) header("X-User-Role", role)
            if (w.body != null) contentType(MediaType.APPLICATION_JSON).content(w.body)
        }

    @ParameterizedTest(name = "{0} as role [{1}] is 403 and never reaches the service")
    @MethodSource("nonAdminCallers")
    fun `write endpoint refuses non-admin callers`(w: Write, role: String?) {
        mockMvc.perform(build(w, role)).andExpect(status().isForbidden)
        verifyNoInteractions(service)
    }

    @ParameterizedTest(name = "{0} as ADMIN is not forbidden")
    @MethodSource("writes")
    fun `write endpoint accepts the admin role`(w: Write) {
        val status = mockMvc.perform(build(w, "ADMIN")).andReturn().response.status

        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200)
    }

    @Test
    fun `reads stay public without any identity headers`() {
        `when`(service.getFeaturedProducts(anyPage())).thenReturn(emptyList())
        `when`(service.getCatalogByCategory(eq(5L), anyPage())).thenReturn(emptyList())

        for (path in listOf(
            "/api/v1/catalog/featured", "/api/v1/catalog/category/5"
        )) {
            mockMvc.perform(request(HttpMethod.GET, path)).andExpect(status().isOk)
        }
        // the paged reads (browse/search) are covered over real HTTP in CatalogDataIntegritySecurityIT
    }

    /** Mockito's any() hands Kotlin a null for a non-null parameter; the elvis supplies a placeholder the matcher ignores. */
    private fun anyPage(): Pageable = org.mockito.ArgumentMatchers.any(Pageable::class.java) ?: PageRequest.of(0, 1)

    private fun eq(value: Long): Long = org.mockito.ArgumentMatchers.eq(value)
}
