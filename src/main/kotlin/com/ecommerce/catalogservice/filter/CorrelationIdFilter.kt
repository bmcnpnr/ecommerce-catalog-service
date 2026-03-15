package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val correlationId = request.getHeader(CORRELATION_ID_HEADER) ?: UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }
}
