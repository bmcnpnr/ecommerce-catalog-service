package com.ecommerce.catalogservice.security

import com.ecommerce.catalogservice.exception.ForbiddenException
import com.ecommerce.catalogservice.exception.UnauthorizedException

/**
 * Endpoint class "admin". The gateway verifies the JWT and sets `X-Username` / `X-User-Role`; the role is
 * compared case-sensitively with no trimming. 401 without any identity, otherwise 403.
 */
object Authorization {
    const val ROLE_HEADER = "X-User-Role"
    const val USERNAME_HEADER = "X-Username"

    fun requireAdmin(username: String?, role: String?) {
        if (role == "ADMIN") return
        if (username.isNullOrBlank() && role.isNullOrBlank()) throw UnauthorizedException()
        throw ForbiddenException()
    }
}
