package net.stewart.mediamanager

import com.google.gson.Gson
import com.google.gson.JsonParser
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * REST API for device pairing (QR code flow).
 *
 * All endpoints are unauthenticated — the pairing flow itself is the auth mechanism.
 * The pair code acts as a short-lived capability token.
 *
 * Rate limits:
 * - /start: max 5 requests per IP per minute, max 50 active pending codes globally
 * - /status: max 30 requests per IP per minute (devices poll every ~3s)
 * - /qr: max 10 requests per IP per minute
 *
 * POST /api/pair/start     — create a new pair code (device calls this)
 * GET  /api/pair/status     — poll for pairing completion (device polls this)
 * GET  /api/pair/qr         — get QR code PNG for a pair code
 */
@WebServlet(urlPatterns = ["/api/pair/*"])
class PairingServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(PairingServlet::class.java)
    private val gson = Gson()

    // Per-IP rate limiting: IP -> deque of request timestamps (epoch millis)
    private val rateLimitStart = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val rateLimitStatus = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val rateLimitQr = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    companion object {
        private const val START_MAX_PER_MINUTE = 5
        private const val STATUS_MAX_PER_MINUTE = 30
        private const val QR_MAX_PER_MINUTE = 10
        private const val MAX_PENDING_CODES = 50
        private const val WINDOW_MS = 60_000L
    }

    private fun getClientIp(req: HttpServletRequest): String {
        return req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: req.remoteAddr
    }

    /**
     * Returns true if the request exceeds the rate limit.
     */
    private fun isRateLimited(bucket: ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>,
                               ip: String, maxPerMinute: Int): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = bucket.computeIfAbsent(ip) { ConcurrentLinkedDeque() }

        // Evict timestamps older than the window
        while (timestamps.peekFirst()?.let { it < now - WINDOW_MS } == true) {
            timestamps.pollFirst()
        }

        if (timestamps.size >= maxPerMinute) {
            return true
        }

        timestamps.addLast(now)
        return false
    }

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try {
            when (path) {
                "start" -> handleStart(req, resp)
                else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
        } catch (e: Exception) {
            log.error("Pairing API error on POST /{}: {}", path, e.message, e)
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                mapOf("error" to "Internal server error"))
        }
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try {
            when (path) {
                "status" -> handleStatus(req, resp)
                "qr" -> handleQr(req, resp)
                else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
        } catch (e: Exception) {
            log.error("Pairing API error on GET /{}: {}", path, e.message, e)
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                mapOf("error" to "Internal server error"))
        }
    }

    private fun handleStart(req: HttpServletRequest, resp: HttpServletResponse) {
        val ip = getClientIp(req)
        if (isRateLimited(rateLimitStart, ip, START_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /start from IP {}", ip)
            sendJson(resp, 429, mapOf("error" to "Too many requests. Try again in a minute."))
            return
        }

        // Global cap on active pending codes to prevent DB flooding
        val pendingCount = PairingService.countPendingCodes()
        if (pendingCount >= MAX_PENDING_CODES) {
            log.warn("Global pending code limit reached ({}) — rejecting /start from IP {}", pendingCount, ip)
            sendJson(resp, 429, mapOf("error" to "Too many active pairing requests. Try again later."))
            return
        }

        val body = try {
            val raw = req.inputStream.readNBytes(4096).toString(Charsets.UTF_8)
            if (raw.isNotBlank()) JsonParser.parseString(raw).asJsonObject else null
        } catch (e: Exception) { null }

        val deviceName = body?.get("device_name")?.asString ?: ""

        val pairCode = PairingService.createPairCode(deviceName)
        val result = mutableMapOf<String, Any>(
            "code" to pairCode.code,
            "expires_in" to 300
        )
        // Include canonical base URL so devices can show the correct pairing URL
        val baseUrl = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.trimEnd('/')
        if (!baseUrl.isNullOrBlank()) {
            result["base_url"] = baseUrl
        }
        sendJson(resp, HttpServletResponse.SC_OK, result)
    }

    private fun handleStatus(req: HttpServletRequest, resp: HttpServletResponse) {
        val ip = getClientIp(req)
        if (isRateLimited(rateLimitStatus, ip, STATUS_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /status from IP {}", ip)
            sendJson(resp, 429, mapOf("error" to "Too many requests. Slow down polling."))
            return
        }

        val code = req.getParameter("code")
        if (code.isNullOrBlank()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "code parameter required"))
            return
        }

        val status = PairingService.checkStatus(code)
        if (status == null) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND,
                mapOf("error" to "Pair code not found or expired"))
            return
        }

        val result = mutableMapOf<String, Any?>("status" to status.status)
        if (status.status == "paired") {
            result["token"] = status.token
            result["username"] = status.username
            // Include canonical base URL so Roku uses the proxy address for posters/streams
            val baseUrl = AppConfig.findAll()
                .firstOrNull { it.config_key == "roku_base_url" }
                ?.config_val?.trimEnd('/')
            if (!baseUrl.isNullOrBlank()) {
                result["base_url"] = baseUrl
            }
        }
        sendJson(resp, HttpServletResponse.SC_OK, result)
    }

    private fun handleQr(req: HttpServletRequest, resp: HttpServletResponse) {
        val ip = getClientIp(req)
        if (isRateLimited(rateLimitQr, ip, QR_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /qr from IP {}", ip)
            resp.sendError(429, "Too many requests")
            return
        }

        val code = req.getParameter("code")
        if (code.isNullOrBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "code parameter required")
            return
        }

        // Use canonical base URL if configured, otherwise derive from request headers
        val configuredBase = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.trimEnd('/')
        val baseUrl = if (!configuredBase.isNullOrBlank()) {
            configuredBase
        } else {
            val scheme = req.getHeader("X-Forwarded-Proto") ?: req.scheme
            val host = req.getHeader("X-Forwarded-Host") ?: req.getHeader("Host") ?: "${req.serverName}:${req.serverPort}"
            "$scheme://$host"
        }
        val pairUrl = "$baseUrl/pair?code=$code"

        val pngBytes = PairingService.generateQrCode(pairUrl)

        resp.contentType = "image/png"
        resp.setContentLength(pngBytes.size)
        resp.setHeader("Cache-Control", "no-cache")
        resp.outputStream.write(pngBytes)
    }

    private fun sendJson(resp: HttpServletResponse, status: Int, data: Any) {
        resp.status = status
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.writer.write(gson.toJson(data))
    }
}
