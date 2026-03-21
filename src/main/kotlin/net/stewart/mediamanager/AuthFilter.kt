package net.stewart.mediamanager

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.PairingService

@WebFilter(urlPatterns = ["/posters/*", "/headshots/*", "/stream/*", "/backdrops/*", "/playback-progress/*", "/local-images/*", "/ownership-photos/*", "/collection-posters/*", "/cam/*", "/live-tv-stream/*"])
class AuthFilter : Filter {

    companion object {
        /** Request attribute key for the authenticated AppUser (synthetic user for Roku API key auth). */
        const val USER_ATTRIBUTE = "net.stewart.mediamanager.authUser"
        /** Request attribute for the auth method used (cookie, jwt_header, jwt_cookie, device_token). */
        const val AUTH_METHOD_ATTRIBUTE = "net.stewart.mediamanager.authMethod"
    }

    override fun init(filterConfig: FilterConfig?) {}

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        // Pre-setup state: no users exist yet, block servlet access (no content to serve)
        if (!AuthService.hasUsers()) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }

        // Cookie-based auth (normal browser sessions)
        val user = AuthService.validateCookieFromRequest(httpRequest)
        if (user != null) {
            httpRequest.setAttribute(USER_ATTRIBUTE, user)
            httpRequest.setAttribute(AUTH_METHOD_ATTRIBUTE, "cookie")
            chain.doFilter(request, response)
            return
        }

        // JWT Bearer auth (iOS app) — from Authorization header only
        val authHeader = httpRequest.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val jwtUser = JwtService.validateAccessToken(authHeader.substring(7).trim())
            if (jwtUser != null) {
                httpRequest.setAttribute(USER_ATTRIBUTE, jwtUser)
                httpRequest.setAttribute(AUTH_METHOD_ATTRIBUTE, "jwt_header")
                chain.doFilter(request, response)
                return
            }
        }

        // JWT cookie auth (iOS HLS streaming) — AVPlayer can't set Authorization headers
        // on HLS sub-requests (playlist refreshes, .ts segments), so the iOS client sets
        // a mm_jwt cookie containing the access token before starting HLS playback.
        val jwtCookie = httpRequest.cookies?.firstOrNull { it.name == "mm_jwt" }
        if (jwtCookie != null) {
            val jwtUser = JwtService.validateAccessToken(jwtCookie.value)
            if (jwtUser != null) {
                httpRequest.setAttribute(USER_ATTRIBUTE, jwtUser)
                httpRequest.setAttribute(AUTH_METHOD_ATTRIBUTE, "jwt_cookie")
                chain.doFilter(request, response)
                return
            }
        }

        // Device token auth (paired Roku/devices) — real user with their preferences
        val apiKey = httpRequest.getParameter("key")
        if (apiKey != null) {
            val deviceUser = PairingService.validateDeviceToken(apiKey)
            if (deviceUser != null) {
                httpRequest.setAttribute(USER_ATTRIBUTE, deviceUser)
                httpRequest.setAttribute(AUTH_METHOD_ATTRIBUTE, "device_token")
                chain.doFilter(request, response)
                return
            }
        }

        httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
    }

    override fun destroy() {}
}
