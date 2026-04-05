package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.linecorp.armeria.common.Cookie
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.LoginResult
import net.stewart.mediamanager.service.PasswordService
import net.stewart.mediamanager.service.RefreshResult
import net.stewart.mediamanager.service.WebAuthnService
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * REST auth endpoints for the Angular SPA.
 *
 * Token strategy:
 * - Access token: returned in JSON body, stored in memory by the SPA
 * - Refresh token: set as HttpOnly cookie (mm_refresh), never exposed to JS
 *
 * All endpoints require transit through the TLS-terminating reverse proxy
 * (verified via X-Forwarded-Proto and X-Forwarded-For headers). Direct
 * connections to the plaintext Armeria port are rejected.
 *
 * Rate limits are applied per-IP at the HTTP level (defense in depth —
 * [AuthService.login] also enforces per-IP and per-username limits internally).
 *
 * No [ArmeriaAuthDecorator] — these endpoints are unauthenticated by design.
 */
@Blocking
class AuthRestService {

    private val log = LoggerFactory.getLogger(AuthRestService::class.java)
    private val gson = Gson()

    companion object {
        private const val REFRESH_COOKIE_NAME = "mm_refresh"
        private const val REFRESH_COOKIE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60 // 30 days

        // Per-IP HTTP-level rate limits (defense in depth)
        private const val LOGIN_MAX_PER_MINUTE = 10
        private const val REFRESH_MAX_PER_MINUTE = 30
        private const val LOGOUT_MAX_PER_MINUTE = 10
        private const val WINDOW_MS = 60_000L

        /**
         * Content-Security-Policy for JSON API responses. Locked down since these
         * endpoints return data, not HTML — no scripts, styles, or media needed.
         */
        private const val API_CSP = "default-src 'none'; frame-ancestors 'none'"
    }

    private val loginRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val refreshRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val logoutRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    /**
     * Pre-login discovery: returns server capabilities and legal document URLs/versions.
     * The Angular login page uses this to show "agree to terms" checkboxes before login.
     */
    @com.linecorp.armeria.server.annotation.Get("/api/v2/auth/discover")
    fun discover(ctx: ServiceRequestContext): HttpResponse {
        val legal = mutableMapOf<String, Any?>()
        LegalRequirements.privacyPolicyUrl?.let { legal["privacy_policy_url"] = it }
        if (LegalRequirements.privacyPolicyVersion > 0) {
            legal["privacy_policy_version"] = LegalRequirements.privacyPolicyVersion
        }
        LegalRequirements.webTermsOfUseUrl?.let { legal["terms_of_use_url"] = it }
        if (LegalRequirements.webTermsOfUseVersion > 0) {
            legal["terms_of_use_version"] = LegalRequirements.webTermsOfUseVersion
        }

        val responseBody = mapOf(
            "setup_required" to !AuthService.hasUsers(),
            "legal" to legal.ifEmpty { null },
            "passkeys_available" to WebAuthnService.isAvailable()
        )
        return jsonResponse(HttpStatus.OK, responseBody)
    }

    /**
     * Initial server setup: creates the first admin user and configures legal document URLs.
     * Only works when no users exist (setup_required=true from discover).
     */
    private val setupRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    @Post("/api/v2/auth/setup")
    fun setup(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(setupRateLimit, proxy.clientIp, LOGIN_MAX_PER_MINUTE)) {
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf("error" to "Too many requests"))
        }

        val body = parseBody(ctx) ?: return badRequest("Invalid request body")

        val username = body.get("username")?.asString
        val password = body.get("password")?.asString
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return badRequest("username and password are required")
        }

        val violations = PasswordService.validate(password, username)
        if (violations.isNotEmpty()) {
            return jsonResponse(HttpStatus.BAD_REQUEST, mapOf("error" to violations.first()))
        }

        // Create first user in a transaction (rejects if users already exist)
        val user = try {
            JdbiOrm.jdbi().inTransaction<AppUser, Exception> { handle ->
                val count = handle.createQuery("SELECT COUNT(*) FROM app_user")
                    .mapTo(Int::class.java).one()
                if (count > 0) throw IllegalStateException("Setup already complete")
                val now = java.time.LocalDateTime.now()
                val u = AppUser(
                    username = username,
                    display_name = "TOBEREMOVED",
                    password_hash = PasswordService.hash(password),
                    access_level = 2, // Admin
                    created_at = now,
                    updated_at = now
                )
                u.save()
                u
            }
        } catch (_: IllegalStateException) {
            return jsonResponse(HttpStatus.CONFLICT, mapOf("error" to "Setup already complete — users exist"))
        }

        // Save legal document URLs (seed with about:blank if not provided)
        val privacyPolicyUrl = body.get("privacy_policy_url")?.asString?.ifBlank { null } ?: "about:blank"
        val termsOfUseUrl = body.get("terms_of_use_url")?.asString?.ifBlank { null } ?: "about:blank"

        if (!isValidLegalUrl(privacyPolicyUrl) || !isValidLegalUrl(termsOfUseUrl)) {
            return badRequest("Legal URLs must be https:// or about:blank")
        }

        saveConfig("privacy_policy_url", privacyPolicyUrl)
        saveConfig("privacy_policy_version", "1")
        saveConfig("web_terms_of_use_url", termsOfUseUrl)
        saveConfig("web_terms_of_use_version", "1")
        LegalRequirements.refresh()

        // Record the user's agreement to the initial versions
        user.privacy_policy_version = 1
        user.terms_of_use_version = 1
        user.privacy_policy_accepted_at = java.time.LocalDateTime.now()
        user.terms_of_use_accepted_at = java.time.LocalDateTime.now()
        user.save()

        // Issue tokens so the user is logged in immediately after setup
        val tokenPair = JwtService.createTokenPair(user, "web")
        val refreshCookie = buildRefreshCookie(tokenPair.refreshToken)

        val responseBody = mapOf(
            "access_token" to tokenPair.accessToken,
            "expires_in" to tokenPair.expiresIn
        )

        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .add("Content-Security-Policy", API_CSP)
            .cookie(refreshCookie)
            .build()
        return HttpResponse.of(headers, HttpData.ofUtf8(gson.toJson(responseBody)))
    }

    private fun isValidLegalUrl(url: String): Boolean =
        url == "about:blank" || url.startsWith("https://")

    private fun saveConfig(key: String, value: String) {
        val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
        if (existing != null) {
            existing.config_val = value
            existing.save()
        } else {
            AppConfig(config_key = key, config_val = value).save()
        }
    }

    @Post("/api/v2/auth/login")
    fun login(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(loginRateLimit, proxy.clientIp, LOGIN_MAX_PER_MINUTE)) {
            log.warn("Login HTTP rate limit exceeded for IP {}", proxy.clientIp)
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf(
                "error" to "Too many requests",
                "retry_after" to 60
            ))
        }

        val body = parseBody(ctx) ?: return badRequest("Invalid request body")
        val username = body.get("username")?.asString
        val password = body.get("password")?.asString
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return badRequest("username and password are required")
        }

        val result = AuthService.login(username, password, proxy.clientIp)

        return when (result) {
            is LoginResult.Success -> {
                val user = result.user
                val deviceName = body.get("device_name")?.asString ?: "web"
                val tokenPair = JwtService.createTokenPair(user, deviceName)
                val refreshCookie = buildRefreshCookie(tokenPair.refreshToken)

                val responseBody = mapOf(
                    "access_token" to tokenPair.accessToken,
                    "expires_in" to tokenPair.expiresIn,
                    "password_change_required" to user.must_change_password
                )

                val headers = ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.JSON_UTF_8)
                    .add("Content-Security-Policy", API_CSP)
                    .cookie(refreshCookie)
                    .build()
                HttpResponse.of(headers, HttpData.ofUtf8(gson.toJson(responseBody)))
            }
            LoginResult.Failed -> {
                jsonResponse(HttpStatus.UNAUTHORIZED, mapOf("error" to "Invalid credentials"))
            }
            is LoginResult.RateLimited -> {
                jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf(
                    "error" to "Too many login attempts",
                    "retry_after" to result.retryAfterSeconds
                ))
            }
        }
    }

    // -- Passkey authentication --

    private val passkeyRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    @Post("/api/v2/auth/passkey/authentication-options")
    fun passkeyAuthenticationOptions(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(passkeyRateLimit, proxy.clientIp, LOGIN_MAX_PER_MINUTE)) {
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf(
                "error" to "Too many requests",
                "retry_after" to 60
            ))
        }

        return try {
            val options = WebAuthnService.generateAuthenticationOptions()
            val responseBody = mapOf(
                "challenge" to options.signedChallenge,
                "options" to gson.fromJson(options.options.toString(), Map::class.java)
            )
            jsonResponse(HttpStatus.OK, responseBody)
        } catch (e: IllegalStateException) {
            jsonResponse(HttpStatus.SERVICE_UNAVAILABLE, mapOf("error" to e.message))
        }
    }

    @Post("/api/v2/auth/passkey/authenticate")
    fun passkeyAuthenticate(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(passkeyRateLimit, proxy.clientIp, LOGIN_MAX_PER_MINUTE)) {
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf(
                "error" to "Too many requests",
                "retry_after" to 60
            ))
        }

        val body = parseBody(ctx) ?: return badRequest("Invalid request body")
        val challenge = body.get("challenge")?.asString
            ?: return badRequest("challenge is required")
        val credential = body.getAsJsonObject("credential")
            ?: return badRequest("credential is required")

        val credentialId = credential.get("id")?.asString
            ?: return badRequest("credential.id is required")
        // WebAuthn JSON nests assertion fields under credential.response
        val response = credential.getAsJsonObject("response")
            ?: return badRequest("credential.response is required")
        val clientDataJSON = response.get("clientDataJSON")?.asString
            ?: return badRequest("credential.response.clientDataJSON is required")
        val authenticatorData = response.get("authenticatorData")?.asString
            ?: return badRequest("credential.response.authenticatorData is required")
        val signature = response.get("signature")?.asString
            ?: return badRequest("credential.response.signature is required")
        val userHandle = response.get("userHandle")?.asString

        return try {
            val user = WebAuthnService.verifyAuthentication(
                signedChallenge = challenge,
                credentialId = credentialId,
                clientDataJSON = clientDataJSON,
                authenticatorData = authenticatorData,
                signature = signature,
                userHandle = userHandle
            )

            if (user.locked) {
                return jsonResponse(HttpStatus.UNAUTHORIZED, mapOf("error" to "Account locked"))
            }

            val tokenPair = JwtService.createTokenPair(user, "web-passkey")
            val refreshCookie = buildRefreshCookie(tokenPair.refreshToken)

            val responseBody = mapOf(
                "access_token" to tokenPair.accessToken,
                "expires_in" to tokenPair.expiresIn,
                "password_change_required" to user.must_change_password
            )

            val headers = ResponseHeaders.builder(HttpStatus.OK)
                .contentType(MediaType.JSON_UTF_8)
                .add("Content-Security-Policy", API_CSP)
                .cookie(refreshCookie)
                .build()
            HttpResponse.of(headers, HttpData.ofUtf8(gson.toJson(responseBody)))
        } catch (e: IllegalArgumentException) {
            log.warn("Passkey authentication failed from IP {}: {}", proxy.clientIp, e.message)
            jsonResponse(HttpStatus.UNAUTHORIZED, mapOf("error" to "Passkey authentication failed"))
        }
    }

    @Post("/api/v2/auth/refresh")
    fun refresh(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(refreshRateLimit, proxy.clientIp, REFRESH_MAX_PER_MINUTE)) {
            log.warn("Refresh HTTP rate limit exceeded for IP {}", proxy.clientIp)
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                mapOf("error" to "Too many requests", "retry_after" to 60))
        }

        val refreshToken = ctx.request().headers().cookies()
            .firstOrNull { it.name() == REFRESH_COOKIE_NAME }
            ?.value()

        if (refreshToken.isNullOrBlank()) {
            return jsonResponse(HttpStatus.UNAUTHORIZED, mapOf("error" to "No refresh token"))
        }

        return when (val result = JwtService.refresh(refreshToken)) {
            is RefreshResult.Success -> {
                val refreshCookie = buildRefreshCookie(result.tokenPair.refreshToken)

                val responseBody = mapOf(
                    "access_token" to result.tokenPair.accessToken,
                    "expires_in" to result.tokenPair.expiresIn
                )

                val headers = ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.JSON_UTF_8)
                    .add("Content-Security-Policy", API_CSP)
                    .cookie(refreshCookie)
                    .build()
                HttpResponse.of(headers, HttpData.ofUtf8(gson.toJson(responseBody)))
            }
            RefreshResult.InvalidToken -> {
                clearRefreshCookie(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token")
            }
            RefreshResult.FamilyRevoked -> {
                clearRefreshCookie(HttpStatus.UNAUTHORIZED, "Session revoked — please log in again")
            }
        }
    }

    @Post("/api/v2/auth/logout")
    fun logout(ctx: ServiceRequestContext): HttpResponse {
        val proxy = requireProxy(ctx) ?: return proxyRequired()

        if (isRateLimited(logoutRateLimit, proxy.clientIp, LOGOUT_MAX_PER_MINUTE)) {
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, mapOf("error" to "Too many requests"))
        }

        val refreshToken = ctx.request().headers().cookies()
            .firstOrNull { it.name() == REFRESH_COOKIE_NAME }
            ?.value()

        if (!refreshToken.isNullOrBlank()) {
            try {
                JwtService.revoke(refreshToken)
            } catch (_: Exception) {}
        }

        return clearRefreshCookie(HttpStatus.OK, null)
    }

    // -- Proxy validation --

    private data class ProxyContext(val clientIp: String)

    /**
     * Verifies the request transited the TLS-terminating reverse proxy by checking
     * X-Forwarded-Proto and X-Forwarded-For headers. Returns null and logs if either
     * is missing — credentials must never be issued over a plaintext direct connection.
     */
    private fun requireProxy(ctx: ServiceRequestContext): ProxyContext? {
        val headers = ctx.request().headers()
        val proto = headers.get("x-forwarded-proto")
        val forwardedFor = headers.get("x-forwarded-for")

        if (proto == null || forwardedFor == null) {
            val remoteAddr = ctx.remoteAddress().address?.hostAddress ?: "unknown"
            log.warn("Auth request rejected: missing proxy headers " +
                "(x-forwarded-proto={}, x-forwarded-for={}, remote={})",
                proto ?: "<absent>", forwardedFor ?: "<absent>", remoteAddr)
            return null
        }

        if (!proto.equals("https", ignoreCase = true)) {
            log.warn("Auth request rejected: x-forwarded-proto is '{}', expected 'https'", proto)
            return null
        }

        val clientIp = forwardedFor.split(",").firstOrNull()?.trim()
        if (clientIp.isNullOrBlank()) {
            log.warn("Auth request rejected: x-forwarded-for is empty")
            return null
        }

        return ProxyContext(clientIp)
    }

    private fun proxyRequired(): HttpResponse =
        jsonResponse(HttpStatus.FORBIDDEN, mapOf("error" to "Direct access not permitted"))

    // -- Cookie helpers --

    private fun buildRefreshCookie(value: String): Cookie =
        Cookie.secureBuilder(REFRESH_COOKIE_NAME, value)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/api/v2/auth/")
            .maxAge(REFRESH_COOKIE_MAX_AGE_SECONDS)
            .build()

    private fun clearRefreshCookie(status: HttpStatus, error: String?): HttpResponse {
        val expiredCookie = Cookie.secureBuilder(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .sameSite("Lax")
            .path("/api/v2/auth/")
            .maxAge(0)
            .build()

        val body = if (error != null) mapOf("error" to error) else mapOf("ok" to true)
        val headers = ResponseHeaders.builder(status)
            .contentType(MediaType.JSON_UTF_8)
            .add("Content-Security-Policy", API_CSP)
            .cookie(expiredCookie)
            .build()
        return HttpResponse.of(headers, HttpData.ofUtf8(gson.toJson(body)))
    }

    // -- Rate limiting --

    private fun isRateLimited(
        bucket: ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>,
        ip: String, maxPerMinute: Int
    ): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = bucket.computeIfAbsent(ip) { ConcurrentLinkedDeque() }
        while (timestamps.peekFirst()?.let { it < now - WINDOW_MS } == true) {
            timestamps.pollFirst()
        }
        if (timestamps.size >= maxPerMinute) return true
        timestamps.addLast(now)
        return false
    }

    // -- Request parsing --

    private fun parseBody(ctx: ServiceRequestContext): com.google.gson.JsonObject? {
        return try {
            val text = ctx.request().aggregate().join().contentUtf8()
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun badRequest(message: String): HttpResponse =
        jsonResponse(HttpStatus.BAD_REQUEST, mapOf("error" to message))

    private fun jsonResponse(status: HttpStatus, data: Any): HttpResponse =
        HttpResponse.builder()
            .status(status)
            .content(MediaType.JSON_UTF_8, gson.toJson(data))
            .header("Content-Security-Policy", API_CSP)
            .build()
}
