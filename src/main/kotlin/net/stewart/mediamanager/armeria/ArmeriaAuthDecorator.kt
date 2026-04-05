package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey
import net.stewart.mediamanager.entity.AppUser
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.PairingService

/**
 * Armeria decorator that authenticates HTTP requests.
 *
 * Replicates the same auth chain as [net.stewart.mediamanager.AuthFilter]:
 * 1. Cookie session (`mm_session` cookie)
 * 2. JWT Bearer header (`Authorization: Bearer <token>`)
 * 3. JWT cookie (`mm_jwt` for HLS sub-requests from AVPlayer)
 * 4. Device token (`?key=` parameter for paired Roku/devices)
 *
 * On success, sets [USER_KEY] and [AUTH_METHOD_KEY] on the request context.
 * On failure, returns 401 (or 403 if no users exist yet).
 */
class ArmeriaAuthDecorator : DecoratingHttpServiceFunction {

    companion object {
        val USER_KEY: AttributeKey<AppUser> =
            AttributeKey.valueOf("armeria.authUser")
        val AUTH_METHOD_KEY: AttributeKey<String> =
            AttributeKey.valueOf("armeria.authMethod")

        /** Retrieve the authenticated user from an Armeria request context. */
        fun getUser(ctx: ServiceRequestContext): AppUser? = ctx.attr(USER_KEY)

        /** Paths exempt from legal compliance checks (user needs these to agree to terms or manage auth). */
        private val LEGAL_EXEMPT_PREFIXES = listOf(
            "/api/v2/legal/",
            "/api/v2/profile/change-password",
            "/api/v2/profile/passkeys/",
            "/api/v2/auth/"
        )

        private fun isLegalExempt(path: String): Boolean =
            LEGAL_EXEMPT_PREFIXES.any { path.startsWith(it) }
    }

    override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: com.linecorp.armeria.common.HttpRequest): HttpResponse {
        // Pre-setup state: no users exist yet
        if (!AuthService.hasUsers()) {
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val headers = req.headers()
        var user: AppUser? = null
        var authMethod: String? = null

        // 1. Cookie-based auth (normal browser sessions)
        val cookies = headers.cookies()
        val sessionCookie = cookies.firstOrNull { it.name() == "mm_auth" }
        if (sessionCookie != null) {
            val sessionUser = AuthService.validateCookieToken(sessionCookie.value())
            if (sessionUser != null) {
                user = sessionUser
                authMethod = "cookie"
            }
        }

        // 2. JWT Bearer auth (iOS app / Angular SPA)
        if (user == null) {
            val authHeader = headers.get("authorization")
            if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                val jwtUser = JwtService.validateAccessToken(authHeader.substring(7).trim())
                if (jwtUser != null) {
                    user = jwtUser
                    authMethod = "jwt_header"
                }
            }
        }

        // 3. JWT cookie auth (iOS HLS streaming — AVPlayer can't set Authorization headers)
        if (user == null) {
            val jwtCookie = cookies.firstOrNull { it.name() == "mm_jwt" }
            if (jwtCookie != null) {
                val jwtUser = JwtService.validateAccessToken(jwtCookie.value())
                if (jwtUser != null) {
                    user = jwtUser
                    authMethod = "jwt_cookie"
                }
            }
        }

        // 4. Device token auth (paired Roku/devices)
        if (user == null) {
            val params = ctx.queryParams()
            val apiKey = params.get("key")
            if (apiKey != null) {
                val deviceUser = PairingService.validateDeviceToken(apiKey)
                if (deviceUser != null) {
                    user = deviceUser
                    authMethod = "device_token"
                }
            }
        }

        if (user == null) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        }

        ctx.setAttr(USER_KEY, user)
        ctx.setAttr(AUTH_METHOD_KEY, authMethod!!)

        // Legal compliance check — skip for device tokens (Roku) and exempt paths
        if (authMethod != "device_token" && !isLegalExempt(ctx.path())) {
            val requiredTou = LegalRequirements.webTermsOfUseVersion
            if (!LegalRequirements.isCompliant(user.id!!, user.isAdmin(), requiredTou)) {
                val body = """{"error":"terms_required"}"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                // HTTP 451 Unavailable For Legal Reasons
                val responseHeaders = ResponseHeaders.builder(HttpStatus.valueOf(451))
                    .contentType(MediaType.JSON_UTF_8)
                    .contentLength(bytes.size.toLong())
                    .build()
                return HttpResponse.of(responseHeaders, HttpData.wrap(bytes))
            }
        }

        return delegate.serve(ctx, req)
    }
}
