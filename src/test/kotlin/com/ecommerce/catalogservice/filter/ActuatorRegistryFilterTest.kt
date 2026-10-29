package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ActuatorRegistryFilterTest {

    private val filter = ActuatorRegistryFilter()

    private fun call(path: String, remote: String): Int {
        val request = MockHttpServletRequest("POST", path).apply { remoteAddr = remote }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, res -> (res as MockHttpServletResponse).status = 204 })
        return response.status
    }

    @Test
    fun `serviceregistry is loopback only`() {
        assertEquals(204, call("/actuator/serviceregistry", "127.0.0.1"))
        assertEquals(204, call("/actuator/serviceregistry", "0:0:0:0:0:0:0:1"))
        assertEquals(403, call("/actuator/serviceregistry", "10.1.2.3"))
        assertEquals(403, call("/actuator/serviceregistry", "evil.example.com"))
    }

    @Test
    fun `other actuator endpoints are not restricted`() {
        assertEquals(204, call("/actuator/health", "10.1.2.3"))
    }
}
