package com.ecommerce.catalog.controller

import com.ecommerce.catalog.service.CatalogService
import com.ecommerce.catalog.service.HELLO_FROM_CATALOG_SERVICE
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(CatalogController::class)
class CatalogControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var catalogService: CatalogService

    @Test
    fun `should return default message`() {
        // Mocking service response
        given(catalogService.getHelloMessage()).willReturn(HELLO_FROM_CATALOG_SERVICE)

        // Perform GET request and verify
        mockMvc.perform(get("/hello"))
                .andDo(print())
                .andExpect(status().isOk)
                .andExpect(content().string(HELLO_FROM_CATALOG_SERVICE))
    }

}
