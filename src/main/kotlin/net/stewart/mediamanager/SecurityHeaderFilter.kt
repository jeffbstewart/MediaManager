package net.stewart.mediamanager

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletResponse

/**
 * Adds standard security headers to all responses.
 *
 * Not adding HSTS (reverse proxy's responsibility) or CSP (Vaadin uses inline scripts/styles).
 */
@WebFilter(urlPatterns = ["/*"], asyncSupported = true)
class SecurityHeaderFilter : Filter {

    override fun init(filterConfig: FilterConfig?) {}

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        if (response is HttpServletResponse) {
            response.setHeader("X-Content-Type-Options", "nosniff")
            response.setHeader("X-Frame-Options", "DENY")
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        }
        chain.doFilter(request, response)
    }

    override fun destroy() {}
}
