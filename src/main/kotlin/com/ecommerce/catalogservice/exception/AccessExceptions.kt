package com.ecommerce.catalogservice.exception

/** 401: no identity at all. */
class UnauthorizedException(message: String = "Authentication required") : RuntimeException(message)

/** 403: an identity that may not do this. */
class ForbiddenException(message: String = "Access denied") : RuntimeException(message)

/** 400 with a message that is safe to show the client as is. */
class InvalidRequestException(message: String) : RuntimeException(message)
