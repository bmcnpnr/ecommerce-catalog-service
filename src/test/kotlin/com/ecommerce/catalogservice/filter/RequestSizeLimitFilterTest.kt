package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException
import java.nio.charset.StandardCharsets

class RequestSizeLimitFilterTest {

    private val filter = RequestSizeLimitFilter(1024)

    /** A request that does not declare its length, like a chunked upload. */
    private fun chunked(body: ByteArray): MockHttpServletRequest {
        val request = object : MockHttpServletRequest("POST", "/api/v1/catalog/sync/1") {
            override fun getContentLengthLong(): Long = -1
        }
        request.setContent(body)
        request.addHeader("Transfer-Encoding", "chunked")
        request.characterEncoding = "UTF-8"
        return request
    }

    @Test
    fun `a declared length above the limit is refused with 413 and nothing is read`() {
        val request = MockHttpServletRequest("POST", "/api/v1/catalog/sync/1")
        request.setContent(ByteArray(2048))
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(413, response.status)
        assertTrue(response.contentAsString.contains("\"status\":413") && response.contentAsString.contains("Request body too large"))
        assertNull(chain.request, "the handler never saw it")
    }

    @Test
    fun `a request without a body is not wrapped`() {
        val get = MockHttpServletRequest("GET", "/api/v1/catalog")
        val chain = MockFilterChain()

        filter.doFilter(get, MockHttpServletResponse(), chain)

        assertSame(get, chain.request)
    }

    @Test
    fun `the 413 carries the same hardening headers as every other API response`() {
        val request = MockHttpServletRequest("POST", "/api/v1/catalog/sync/1")
        request.setContent(ByteArray(2048))
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `a declared length within the limit passes through untouched`() {
        val request = MockHttpServletRequest("POST", "/api/v1/catalog/sync/1")
        request.setContent(ByteArray(1024))
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertSame(request, chain.request)
    }

    @Test
    fun `a body without a declared length is cut off once it passes the limit`() {
        var failure: IOException? = null
        var read = 0L
        val chain = FilterChain { req, _ ->
            try {
                (req as HttpServletRequest).inputStream.use { input ->
                    val buffer = ByteArray(256)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        read += n
                    }
                }
            } catch (e: IOException) {
                failure = e
            }
        }

        filter.doFilter(chunked(ByteArray(5000)), MockHttpServletResponse(), chain)

        assertTrue(failure!!.message!!.contains("exceeds 1024 bytes"))
        assertTrue(read <= 1024 + 256, "it stopped soon after the limit, not at 5000 (read $read)")
    }

    @Test
    fun `a body without a declared length within the limit is read in full, as stream and as reader`() {
        val body = "{\"featured\": true, \"note\": \"héllo\"}".toByteArray(StandardCharsets.UTF_8)
        var viaStream: ByteArray? = null
        var viaReader: String? = null

        filter.doFilter(chunked(body), MockHttpServletResponse(), FilterChain { req, _ -> viaStream = req.inputStream.readAllBytes() })
        filter.doFilter(chunked(body), MockHttpServletResponse(), FilterChain { req, _ -> viaReader = req.reader.use { it.readLine() } })

        assertArrayEquals(body, viaStream)
        assertEquals(String(body, StandardCharsets.UTF_8), viaReader)
    }

    @Test
    fun `the limit can be raised`() {
        assertThrows<IOException> {
            RequestSizeLimitFilter(1024).doFilter(chunked(ByteArray(2000)), MockHttpServletResponse(), FilterChain { req, _ -> req.inputStream.readAllBytes() })
        }
        RequestSizeLimitFilter(4096).doFilter(chunked(ByteArray(2000)), MockHttpServletResponse(), FilterChain { req, _ -> req.inputStream.readAllBytes() })
    }
}
