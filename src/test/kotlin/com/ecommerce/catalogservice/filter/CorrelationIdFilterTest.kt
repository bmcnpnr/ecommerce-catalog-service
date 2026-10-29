package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class CorrelationIdFilterTest {

    private val filter = CorrelationIdFilter()
    private val request = MockHttpServletRequest("GET", "/api/v1/catalog")
    private val response = MockHttpServletResponse()

    /** Records the MDC value the downstream chain (controller, handlers, logs) sees. */
    private var seenInChain: String? = null
    private val recordingChain = FilterChain { _, _ -> seenInChain = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY) }

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `an incoming correlation id is used for the request and echoed back`() {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-from-gateway")

        filter.doFilter(request, response, recordingChain)

        assertEquals("corr-from-gateway", seenInChain)
        assertEquals("corr-from-gateway", response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
    }

    @Test
    fun `a request without one gets a fresh UUID, the same in the MDC and the response`() {
        filter.doFilter(request, response, recordingChain)

        val generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)
        assertNotNull(generated)
        assertDoesNotThrow { UUID.fromString(generated) }
        assertEquals(generated, seenInChain)
    }

    @Test
    fun `two requests without a header get different ids`() {
        filter.doFilter(request, response, recordingChain)
        val second = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/catalog"), second, recordingChain)

        assertNotEquals(
            response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
            second.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)
        )
    }

    @Test
    fun `an empty, hostile or over-long header is replaced by a fresh UUID`() {
        for (bad in listOf("", "   ", "has space", "<script>alert(1)</script>", "a/b", "a".repeat(65))) {
            val req = MockHttpServletRequest("GET", "/api/v1/catalog")
            req.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, bad)
            val res = MockHttpServletResponse()

            filter.doFilter(req, res, recordingChain)

            val id = res.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)
            assertNotEquals(bad, id)
            assertDoesNotThrow { UUID.fromString(id) }
            assertEquals(id, seenInChain)
        }
    }

    @Test
    fun `a 64 character id of allowed characters is kept`() {
        val ok = "A-b_c.9".repeat(9).take(64)
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, ok)

        filter.doFilter(request, response, recordingChain)

        assertEquals(ok, response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
    }

    @Test
    fun `the MDC is cleared after the request so a pooled thread does not leak the id`() {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-1")

        filter.doFilter(request, response, recordingChain)

        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY))
    }

    @Test
    fun `the MDC is cleared even when the chain throws`() {
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-2")

        assertThrows<IllegalStateException> {
            filter.doFilter(request, response, FilterChain { _, _ -> throw IllegalStateException("boom") })
        }
        assertNull(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY))
        assertEquals("corr-2", response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER), "set before the chain runs")
    }
}
