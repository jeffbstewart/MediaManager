package net.stewart.mediamanager.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import org.slf4j.LoggerFactory

/** Context key for the authenticated user. Service methods read this via [currentUser]. */
val USER_CONTEXT_KEY: Context.Key<AppUser> = Context.key("authenticated-user")

/** Convenience accessor for the authenticated user in the current gRPC context. */
fun currentUser(): AppUser = USER_CONTEXT_KEY.get()
    ?: error("BUG: currentUser() called in an unauthenticated RPC")

private val AUTHORIZATION_KEY: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/**
 * `Cookie` HTTP header — Armeria forwards every HTTP/2 header into gRPC
 * metadata. Browser SPAs can't read the JWT (it lives in a non-JS-visible
 * `mm_auth` cookie), so the interceptor parses the cookie here as a
 * fallback to the Bearer header.
 */
private val COOKIE_KEY: Metadata.Key<String> =
    Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER)

/**
 * `Origin` HTTP header — set by browsers on every cross-origin and same-
 * origin POST. Used as the CSRF gate for cookie auth: when present, it
 * MUST match the request authority or cookie auth is denied. Native
 * clients (iOS / Android TV / Roku / curl) don't send Origin, so the
 * absent-header case skips the check.
 */
private val ORIGIN_KEY: Metadata.Key<String> =
    Metadata.Key.of("origin", Metadata.ASCII_STRING_MARSHALLER)

// Browser session cookie — sourced from AuthService.COOKIE_NAME so the
// rename happens in one place. Currently "mm_session" (renamed from
// "mm_auth" to invalidate every existing browser session in one shot).

/** Optional metadata key sent by clients to identify their platform for legal compliance. */
private val CLIENT_PLATFORM_KEY: Metadata.Key<String> =
    Metadata.Key.of("x-client-platform", Metadata.ASCII_STRING_MARSHALLER)

/**
 * gRPC [ServerInterceptor] that validates JWT Bearer tokens and enforces access policies.
 *
 * Three RPC categories:
 * 1. **Unauthenticated** — no token required (Login, Refresh, Revoke, Discover)
 * 2. **Authenticated, gate-exempt** — token required but allowed even with must_change_password
 *    (ChangePassword, all ProfileService RPCs, InfoService/GetInfo)
 * 3. **Authenticated, gated** — token required AND blocked if must_change_password
 *
 * Admin enforcement: all RPCs under AdminService require [AppUser.isAdmin].
 */
class AuthInterceptor : ServerInterceptor {

    private val log = LoggerFactory.getLogger(AuthInterceptor::class.java)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val transport = GrpcRequestContext.resolve(headers, call)
        if (transport == null) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("gRPC requires HTTPS via trusted reverse proxy"),
                Metadata()
            )
            return noop()
        }

        // Category 1: Unauthenticated RPCs — no token needed
        if (method in UNAUTHENTICATED_METHODS) {
            val ctx = Context.current().withValue(CLIENT_IP_CONTEXT_KEY, transport.clientIp)
            return Contexts.interceptCall(ctx, call, headers, next)
        }

        // All other RPCs require auth. Two paths, in precedence order:
        //   - Bearer JWT (iOS / Android TV / Roku / direct gRPC clients)
        //   - HttpOnly session cookie (Angular SPA — AuthService.COOKIE_NAME,
        //     DB-validated). The CSRF gate enforces an Origin match when
        //     present so cross-origin POSTs can't piggyback the cookie.
        //
        // The legacy "mm_jwt" JS-set cookie is no longer accepted on
        // gRPC — it never had defense against XSS theft, and the SPA
        // now uses the HttpOnly session cookie. iOS HLS is unaffected
        // (it uses HTTP servlets, not gRPC).
        val user = resolveBearerUser(headers) ?: resolveCookieUser(headers, transport)
        if (user == null) {
            // Diagnostic for production debugging: log what we saw so a
            // failing client (cookie present but rejected, vs. nothing at
            // all) is distinguishable in Binnacle. Cookie value itself is
            // never logged.
            logAuthFailure(method, headers, transport)
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid credentials"), Metadata())
            return noop()
        }

        // Admin enforcement: all AdminService RPCs require admin role
        if (method.startsWith(ADMIN_SERVICE_PREFIX) && !user.isAdmin()) {
            call.close(Status.PERMISSION_DENIED.withDescription("Admin access required"), Metadata())
            return noop()
        }

        // Legal compliance: block if user hasn't agreed to current terms
        // (admins are exempt — they need access to configure the URLs)
        // Use client-supplied platform header to check the correct platform's TOU version.
        val clientPlatform = headers.get(CLIENT_PLATFORM_KEY)
        val requiredTou = net.stewart.mediamanager.service.LegalRequirements.touVersionForPlatform(clientPlatform)
        if (!net.stewart.mediamanager.service.LegalRequirements.isCompliant(user.id!!, user.isAdmin(), requiredTou) && !isGateExempt(method)) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("legal_agreement_required"),
                Metadata()
            )
            return noop()
        }

        // Category 3 (gated): block if must_change_password, unless gate-exempt
        if (user.must_change_password && !isGateExempt(method)) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("password_change_required"),
                Metadata()
            )
            return noop()
        }

        // Attach user to context and proceed
        val ctx = Context.current()
            .withValue(USER_CONTEXT_KEY, user)
            .withValue(CLIENT_IP_CONTEXT_KEY, transport.clientIp)
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    private fun extractBearer(headers: Metadata): String? {
        val value = headers.get(AUTHORIZATION_KEY) ?: return null
        return if (value.startsWith("Bearer ", ignoreCase = true)) {
            value.substring(7)
        } else {
            null
        }
    }

    /** Resolve user from Bearer JWT, or null when the header is absent / token invalid. */
    private fun resolveBearerUser(headers: Metadata): AppUser? {
        val token = extractBearer(headers) ?: return null
        return JwtService.validateAccessToken(token)
    }

    /**
     * Resolve user from the `mm_auth` session cookie carried in the `Cookie`
     * header, or null when the cookie is absent / session expired / CSRF
     * check fails. Used by the browser SPA, which can't see the JWT
     * (HttpOnly).
     *
     * CSRF gate: when the request carries an `Origin` header, it must match
     * the request authority. This blocks cross-origin POSTs from rogue
     * pages even if they somehow obtained the cookie (e.g. a future config
     * change weakens SameSite or installs a permissive CORS policy). Native
     * clients (iOS / Roku / curl) don't send Origin, so the absent-header
     * case skips the check — Bearer JWT clients aren't CSRF-able by
     * construction.
     */
    private fun resolveCookieUser(
        headers: Metadata,
        transport: GrpcRequestContext.TransportContext,
    ): AppUser? {
        val cookieHeader = headers.get(COOKIE_KEY) ?: return null
        val sessionToken = parseCookie(cookieHeader, AuthService.COOKIE_NAME) ?: return null
        if (!originPermitted(headers.get(ORIGIN_KEY), transport.authority)) {
            log.warn("Cookie auth denied — Origin {} does not match authority {}",
                headers.get(ORIGIN_KEY), transport.authority)
            return null
        }
        return AuthService.validateCookieToken(sessionToken)
    }

    /**
     * Compare an Origin header value against the request authority. Returns
     * true when the request is safe to authenticate via cookie:
     *   - Origin is absent (non-browser caller — can't be CSRF'd via fetch).
     *   - Origin's hostname matches the authority's hostname.
     *
     * Returns false when Origin is present but points at a different host —
     * this is the CSRF rejection path.
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
    internal fun originPermitted(origin: String?, authority: String?): Boolean {
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

    /**
     * Diagnostic single-line log when auth resolved nobody. Captures
     * the path that failed (cookie present? Bearer present? Origin
     * mismatch?) without leaking token or cookie values. Read from
     * Binnacle when chasing a 401/UNAUTHENTICATED report from a client.
     */
    private fun logAuthFailure(
        method: String,
        headers: Metadata,
        transport: GrpcRequestContext.TransportContext,
    ) {
        val hasBearer = extractBearer(headers) != null
        val cookieHeader = headers.get(COOKIE_KEY)
        val hasSessionCookie = cookieHeader?.let { parseCookie(it, AuthService.COOKIE_NAME) != null } == true
        val origin = headers.get(ORIGIN_KEY)
        val originOk = originPermitted(origin, transport.authority)
        log.info(
            "AUTH_DENIED rpc={} bearer={} session_cookie={} origin={} authority={} origin_permitted={}",
            method, hasBearer, hasSessionCookie, origin ?: "(none)",
            transport.authority ?: "(none)", originOk,
        )
    }

    /**
     * Pull a single cookie value out of an RFC 6265 `Cookie` header. The
     * header is `name1=value1; name2=value2; …` — split on `;`, trim, and
     * find the entry whose key matches.
     */
    private fun parseCookie(cookieHeader: String, name: String): String? {
        for (raw in cookieHeader.split(';')) {
            val entry = raw.trim()
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            if (entry.substring(0, eq) == name) {
                return entry.substring(eq + 1)
            }
        }
        return null
    }

    private fun isGateExempt(method: String): Boolean {
        return method in GATE_EXEMPT_METHODS || method.startsWith(PROFILE_SERVICE_PREFIX)
    }

    private fun <ReqT> noop(): ServerCall.Listener<ReqT> = object : ServerCall.Listener<ReqT>() {}

    companion object {
        private const val ADMIN_SERVICE_PREFIX = "mediamanager.AdminService/"
        private const val PROFILE_SERVICE_PREFIX = "mediamanager.ProfileService/"

        /** Category 1: No token required at all. */
        private val UNAUTHENTICATED_METHODS = setOf(
            "mediamanager.AuthService/Login",
            "mediamanager.AuthService/Refresh",
            "mediamanager.AuthService/Revoke",
            "mediamanager.AuthService/CreateFirstUser",
            "mediamanager.AuthService/GetPasskeyAuthenticationOptions",
            "mediamanager.AuthService/AuthenticateWithPasskey",
            "mediamanager.InfoService/Discover"
        )

        /** Category 2: Token required, but allowed even with must_change_password. */
        private val GATE_EXEMPT_METHODS = setOf(
            "mediamanager.AuthService/ChangePassword",
            "mediamanager.AuthService/GetLegalStatus",
            "mediamanager.AuthService/AgreeToTerms",
            "mediamanager.InfoService/GetInfo"
            // All ProfileService RPCs are handled by prefix check in isGateExempt()
        )
    }
}
