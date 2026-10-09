package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders

/**
 * CSRF checks for requests authenticated by an ambient browser cookie
 * (`mm_session`, `mm_jwt`). Bearer-token and `?key=` device-token requests
 * carry an explicit credential and are not subject to these checks.
 *
 * Two independent gates, both applied only to state-changing methods:
 *
 * 1. **Same origin.** When an `Origin` header is present its host must match
 *    the request authority's host ([originPermitted], shared with the gRPC
 *    [net.stewart.mediamanager.grpc.AuthInterceptor]). When `Origin` is
 *    absent, a `Sec-Fetch-Site` header other than `same-origin` / `none`
 *    is also rejected.
 * 2. **No "simple" bodies.** A cross-site page can send `text/plain`,
 *    `application/x-www-form-urlencoded`, or `multipart/form-data` (or an
 *    untyped body) without a CORS preflight. None of our cookie-authenticated
 *    endpoints accept those, so they are refused; JSON and binary upload
 *    types (`application/json`, `application/octet-stream`, `image/...`)
 *    always require a preflight cross-origin and pass.
 *
 * SameSite=Lax on the session cookie remains the first line of defense;
 * these checks cover same-site (sibling subdomain) senders that Lax
 * doesn't stop.
 */
object CookieCsrfGuard {

    enum class Verdict {
        /** Request may proceed. */
        OK,
        /** Origin / Sec-Fetch-Site shows a cross-origin sender. Respond 403. */
        CROSS_ORIGIN,
        /** Body is a CORS-safelisted (preflight-free) type or untyped. Respond 415. */
        SIMPLE_BODY,
        /** No Content-Type and unknown length: OK only if the body turns out empty. */
        NEEDS_EMPTY_BODY,
    }

    private val SAFE_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

    /** Content types a browser will send cross-origin without a preflight. */
    private val CORS_SAFELISTED_TYPES = setOf(
        "text/plain",
        "application/x-www-form-urlencoded",
        "multipart/form-data",
    )

    fun isStateChanging(method: HttpMethod): Boolean = method !in SAFE_METHODS

    /** Header-only evaluation of a cookie-authenticated request. */
    fun evaluate(headers: RequestHeaders): Verdict {
        if (!isStateChanging(headers.method())) return Verdict.OK

        val origin = headers.get("origin")
        if (origin != null) {
            if (!originPermitted(origin, headers.authority())) return Verdict.CROSS_ORIGIN
        } else {
            val site = headers.get("sec-fetch-site")?.lowercase()
            if (site != null && site != "same-origin" && site != "none") return Verdict.CROSS_ORIGIN
        }

        val contentType = headers.contentType()
        if (contentType != null) {
            val essence = "${contentType.type()}/${contentType.subtype()}".lowercase()
            return if (essence in CORS_SAFELISTED_TYPES) Verdict.SIMPLE_BODY else Verdict.OK
        }
        val length = headers.contentLength()
        return when {
            length == 0L -> Verdict.OK
            length > 0L -> Verdict.SIMPLE_BODY
            else -> Verdict.NEEDS_EMPTY_BODY
        }
    }

    /**
     * Compare an Origin header value against the request authority. Returns
     * true when the request is safe to authenticate via cookie:
     *   - Origin is absent (non-browser caller — can't be CSRF'd via fetch).
     *   - Origin's hostname matches the authority's hostname.
     *
     * Returns false when Origin is present but points at a different host
     * (or is the opaque `null` origin) — this is the CSRF rejection path.
     *
     * Hostname-only comparison: HTTP/2 reverse proxies (HAProxy in our
     * deploy) often rewrite the `:authority` pseudo-header, dropping or
     * changing the port from the public-facing one the browser puts in
     * Origin. Matching hosts and ignoring ports keeps the CSRF gate
     * meaningful (an attacker can't trick the gate from a different
     * hostname) while tolerating the proxy rewrite.
     *
     * If the authority can't be determined the check fails closed (false),
     * since we can't prove same-origin without it.
     */
    fun originPermitted(origin: String?, authority: String?): Boolean {
        if (origin == null) return true
        if (authority.isNullOrBlank()) return false
        val originHost = parseOriginHost(origin) ?: return false
        val authorityHost = stripPort(authority)
        return originHost.equals(authorityHost, ignoreCase = true)
    }

    /** Pull just the hostname out of `scheme://host[:port]`. */
    private fun parseOriginHost(origin: String): String? {
        return try {
            java.net.URI(origin).host
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strip the trailing `:port` from a `host[:port]` authority value.
     * Handles bracketed IPv6 literals (`[::1]:8443` → `[::1]`).
     */
    private fun stripPort(hostPort: String): String {
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            if (close >= 0) return hostPort.substring(0, close + 1)
            return hostPort
        }
        val colon = hostPort.lastIndexOf(':')
        return if (colon < 0) hostPort else hostPort.substring(0, colon)
    }
}
