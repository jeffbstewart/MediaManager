package net.stewart.mediamanager.armeria

import com.linecorp.armeria.server.ServiceRequestContext
import net.stewart.mediamanager.service.TrustedProxies

/**
 * Client IP for logging and rate limiting. Honors `X-Forwarded-For` only
 * when the TCP peer is a configured trusted proxy (see [TrustedProxies]).
 */
internal fun ServiceRequestContext.bestEffortClientIp(): String =
    TrustedProxies.bestEffortClientIp(
        remoteAddress().address,
        request().headers().getAll("x-forwarded-for"),
    )

/**
 * Value of a forwarded header such as `x-forwarded-proto` or
 * `x-forwarded-host`, or null unless the TCP peer is a trusted proxy.
 */
internal fun ServiceRequestContext.trustedForwardedHeader(name: String): String? =
    TrustedProxies.forwardedHeaderIfTrusted(remoteAddress().address, request().headers().get(name))
