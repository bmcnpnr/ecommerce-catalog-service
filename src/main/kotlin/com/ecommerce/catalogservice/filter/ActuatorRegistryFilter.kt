package com.ecommerce.catalogservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress

/**
 * `/actuator/serviceregistry` can mark the instance OUT_OF_SERVICE. The Kubernetes preStop hook calls it on
 * localhost, so it stays exposed, but only loopback callers may use it (S-6).
 */
@Component
class ActuatorRegistryFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/actuator/serviceregistry")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!isLoopback(request.remoteAddr)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private val IP_LITERAL = Regex("[0-9A-Fa-f:.]+(%[A-Za-z0-9]+)?")

        fun isLoopback(remoteAddr: String?): Boolean {
            if (remoteAddr.isNullOrBlank() || !IP_LITERAL.matches(remoteAddr)) return false
            return try {
                // an IP literal never triggers a DNS lookup
                InetAddress.getByName(remoteAddr).isLoopbackAddress
            } catch (e: Exception) {
                false
            }
        }
    }
}
