package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Caps the size of a request body (1 MB by default, `http.max-request-body-bytes`). Tomcat limits form posts but
 * not JSON, and a body is read into memory before any validation runs, so a few large requests against a service
 * reachable without the gateway's own size limit (a published port, another pod) are enough to exhaust the heap.
 *
 * A declared `Content-Length` above the limit is answered 413 without reading anything. A body without one
 * (chunked) is cut off with an I/O error once it passes the limit, which the usual "malformed body" handler answers.
 * Every legitimate body of this API is a few hundred bytes up to a few KB.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class RequestSizeLimitFilter(@Value("\${http.max-request-body-bytes:1048576}") private val maxBytes: Long) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val declared = request.contentLengthLong
        if (declared > maxBytes) {
            response.status = 413
            response.setHeader("X-Content-Type-Options", "nosniff")
            response.setHeader("Cache-Control", "no-store")
            response.contentType = "application/json"
            response.writer.write("""{"status":413,"error":"Payload Too Large","message":"Request body too large"}""")
            return
        }
        // no declared length and a Transfer-Encoding header: a chunked body of unknown size (a GET has neither)
        val chunked = declared < 0 && request.getHeader("Transfer-Encoding") != null
        chain.doFilter(if (chunked) LimitedRequest(request, maxBytes) else request, response)
    }

    /** Hands out an input stream that fails once more than `maxBytes` have been read. */
    private class LimitedRequest(request: HttpServletRequest, private val maxBytes: Long) : HttpServletRequestWrapper(request) {
        private var stream: ServletInputStream? = null

        override fun getInputStream(): ServletInputStream =
            stream ?: LimitedStream(super.getInputStream(), maxBytes).also { stream = it }

        override fun getReader(): BufferedReader {
            val charset = characterEncoding?.let { Charset.forName(it) } ?: StandardCharsets.ISO_8859_1
            return BufferedReader(InputStreamReader(inputStream, charset))
        }
    }

    private class LimitedStream(private val delegate: ServletInputStream, private val maxBytes: Long) : ServletInputStream() {
        private var read = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = delegate.read(buffer, offset, length)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            read += n
            if (read > maxBytes) throw IOException("Request body exceeds $maxBytes bytes")
        }

        override fun isFinished(): Boolean = delegate.isFinished
        override fun isReady(): Boolean = delegate.isReady
        override fun setReadListener(listener: ReadListener?) = delegate.setReadListener(listener)
    }
}
