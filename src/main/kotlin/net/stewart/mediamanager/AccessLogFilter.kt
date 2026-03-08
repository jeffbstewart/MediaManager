package net.stewart.mediamanager

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.FilterConfig
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.RequestLogBuffer
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Apache-style access log filter. Outputs a simplified Combined Log Format line
 * to stdout for every HTTP request. Cookies are redacted, UUIDs in the request
 * URI are replaced with [REDACTED], and the username is resolved from the
 * session cookie or device token.
 */
@WebFilter(urlPatterns = ["/*"])
class AccessLogFilter : Filter {

    companion object {
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val CLF_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH)
    }

    override fun init(filterConfig: FilterConfig?) {}

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val startNanos = System.nanoTime()
        val wrapper = StatusCapturingResponseWrapper(httpResponse)
        var status = 500
        try {
            chain.doFilter(request, wrapper)
            status = wrapper.capturedStatus
        } catch (e: Exception) {
            throw e
        } finally {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val username = resolveUsername(httpRequest)
            val uri = redactUuids(buildRequestUri(httpRequest))
            val timestamp = ZonedDateTime.now().format(CLF_DATE_FORMAT)
            val protocol = httpRequest.protocol
            val method = httpRequest.method
            val contentLength = wrapper.capturedContentLength
            val lengthStr = if (contentLength > 0) contentLength.toString() else "-"

            val clientIp = httpRequest.getHeader("X-Forwarded-For")
                ?.split(",")?.firstOrNull()?.trim()
                ?: httpRequest.remoteAddr

            val userAgent = httpRequest.getHeader("User-Agent") ?: "-"

            println("$clientIp - $username [$timestamp] \"$method $uri $protocol\" $status $lengthStr")

            RequestLogBuffer.add(RequestLogBuffer.RequestLogEntry(
                timestamp = LocalDateTime.now(),
                clientIp = clientIp,
                username = username,
                method = method,
                uri = uri,
                protocol = protocol,
                status = status,
                responseSize = contentLength,
                userAgent = userAgent,
                elapsedMs = elapsedMs
            ))
        }
    }

    private fun buildRequestUri(request: HttpServletRequest): String {
        val query = request.queryString
        return if (query != null) "${request.requestURI}?$query" else request.requestURI
    }

    private fun redactUuids(uri: String): String =
        UUID_PATTERN.replace(uri, "[REDACTED]")

    private fun resolveUsername(request: HttpServletRequest): String {
        if (!AuthService.hasUsers()) return "-"

        val user = AuthService.validateCookieFromRequest(request)
        if (user != null) return user.username

        val apiKey = request.getParameter("key")
        if (apiKey != null) {
            val deviceUser = PairingService.validateDeviceToken(apiKey)
            if (deviceUser != null) return deviceUser.username
        }

        return "-"
    }

    override fun destroy() {}

    private class StatusCapturingResponseWrapper(
        response: HttpServletResponse
    ) : HttpServletResponseWrapper(response) {

        var capturedStatus: Int = 200
            private set
        var capturedContentLength: Long = -1
            private set

        override fun setStatus(sc: Int) {
            capturedStatus = sc
            super.setStatus(sc)
        }

        override fun sendError(sc: Int) {
            capturedStatus = sc
            super.sendError(sc)
        }

        override fun sendError(sc: Int, msg: String?) {
            capturedStatus = sc
            super.sendError(sc, msg)
        }

        override fun setContentLength(len: Int) {
            capturedContentLength = len.toLong()
            super.setContentLength(len)
        }

        override fun setContentLengthLong(len: Long) {
            capturedContentLength = len
            super.setContentLengthLong(len)
        }

        override fun setHeader(name: String, value: String?) {
            if (name.equals("Content-Length", ignoreCase = true) && value != null) {
                capturedContentLength = value.toLongOrNull() ?: -1
            }
            super.setHeader(name, value)
        }

        override fun addHeader(name: String, value: String?) {
            if (name.equals("Content-Length", ignoreCase = true) && value != null) {
                capturedContentLength = value.toLongOrNull() ?: -1
            }
            super.addHeader(name, value)
        }
    }
}
