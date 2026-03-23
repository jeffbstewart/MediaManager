package net.stewart.mediamanager

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.slf4j.LoggerFactory

/**
 * Servlet filter that translates X-Forwarded-* headers from a reverse proxy
 * into the standard request properties (scheme, remoteAddr, serverName, etc.).
 *
 * Only activates when the MM_BEHIND_PROXY environment variable is "true".
 * This prevents clients from spoofing these headers on direct connections.
 */
@WebFilter(urlPatterns = ["/*"], asyncSupported = true)
class ProxyHeaderFilter : Filter {

    private val log = LoggerFactory.getLogger(ProxyHeaderFilter::class.java)
    private var enabled = false

    override fun init(filterConfig: FilterConfig?) {
        enabled = System.getenv("MM_BEHIND_PROXY")?.equals("true", ignoreCase = true) == true
        if (enabled) {
            log.info("Proxy header filter enabled (MM_BEHIND_PROXY=true)")
        }
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        if (!enabled || request !is HttpServletRequest) {
            chain.doFilter(request, response)
            return
        }

        val forwardedFor = request.getHeader("X-Forwarded-For")
        val forwardedProto = request.getHeader("X-Forwarded-Proto")
        val forwardedHost = request.getHeader("X-Forwarded-Host")
        val forwardedPort = request.getHeader("X-Forwarded-Port")

        // Only wrap if at least one forwarded header is present
        if (forwardedFor == null && forwardedProto == null && forwardedHost == null && forwardedPort == null) {
            chain.doFilter(request, response)
            return
        }

        chain.doFilter(ProxyRequestWrapper(request, forwardedFor, forwardedProto, forwardedHost, forwardedPort), response)
    }

    override fun destroy() {}

    private class ProxyRequestWrapper(
        request: HttpServletRequest,
        private val forwardedFor: String?,
        private val forwardedProto: String?,
        private val forwardedHost: String?,
        private val forwardedPort: String?
    ) : HttpServletRequestWrapper(request) {

        override fun getRemoteAddr(): String {
            // X-Forwarded-For is a comma-separated list; first entry is the original client
            return forwardedFor?.split(",")?.firstOrNull()?.trim() ?: super.getRemoteAddr()
        }

        override fun getScheme(): String {
            return forwardedProto?.trim() ?: super.getScheme()
        }

        override fun isSecure(): Boolean {
            return scheme.equals("https", ignoreCase = true)
        }

        override fun getServerName(): String {
            // X-Forwarded-Host may include port — strip it
            return forwardedHost?.split(":")?.firstOrNull()?.trim() ?: super.getServerName()
        }

        override fun getServerPort(): Int {
            // Explicit port header takes precedence
            forwardedPort?.trim()?.toIntOrNull()?.let { return it }
            // Infer from scheme
            return when {
                forwardedProto?.trim().equals("https", ignoreCase = true) -> 443
                forwardedProto?.trim().equals("http", ignoreCase = true) -> 80
                else -> super.getServerPort()
            }
        }
    }
}
