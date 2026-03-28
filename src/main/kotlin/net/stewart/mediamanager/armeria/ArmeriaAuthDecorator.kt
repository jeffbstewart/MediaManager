package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
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
    }

    override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: com.linecorp.armeria.common.HttpRequest): HttpResponse {
        // Pre-setup state: no users exist yet
        if (!AuthService.hasUsers()) {
            return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        val headers = req.headers()

        // 1. Cookie-based auth (normal browser sessions)
        val cookies = headers.cookies()
        val sessionCookie = cookies.firstOrNull { it.name() == "mm_auth" }
        if (sessionCookie != null) {
            val user = AuthService.validateCookieToken(sessionCookie.value())
            if (user != null) {
                ctx.setAttr(USER_KEY, user)
                ctx.setAttr(AUTH_METHOD_KEY, "cookie")
                return delegate.serve(ctx, req)
            }
        }

        // 2. JWT Bearer auth (iOS app)
        val authHeader = headers.get("authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val jwtUser = JwtService.validateAccessToken(authHeader.substring(7).trim())
            if (jwtUser != null) {
                ctx.setAttr(USER_KEY, jwtUser)
                ctx.setAttr(AUTH_METHOD_KEY, "jwt_header")
                return delegate.serve(ctx, req)
            }
        }

        // 3. JWT cookie auth (iOS HLS streaming — AVPlayer can't set Authorization headers)
        val jwtCookie = cookies.firstOrNull { it.name() == "mm_jwt" }
        if (jwtCookie != null) {
            val jwtUser = JwtService.validateAccessToken(jwtCookie.value())
            if (jwtUser != null) {
                ctx.setAttr(USER_KEY, jwtUser)
                ctx.setAttr(AUTH_METHOD_KEY, "jwt_cookie")
                return delegate.serve(ctx, req)
            }
        }

        // 4. Device token auth (paired Roku/devices)
        val params = ctx.queryParams()
        val apiKey = params.get("key")
        if (apiKey != null) {
            val deviceUser = PairingService.validateDeviceToken(apiKey)
            if (deviceUser != null) {
                ctx.setAttr(USER_KEY, deviceUser)
                ctx.setAttr(AUTH_METHOD_KEY, "device_token")
                return delegate.serve(ctx, req)
            }
        }

        return HttpResponse.of(HttpStatus.UNAUTHORIZED)
    }
}
