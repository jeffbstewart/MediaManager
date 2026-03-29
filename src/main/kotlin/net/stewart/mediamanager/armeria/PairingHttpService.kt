package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Armeria port of [net.stewart.mediamanager.PairingServlet].
 *
 * All endpoints are unauthenticated — the pairing flow itself is the auth mechanism.
 * Rate limits are enforced per client IP.
 */
class PairingHttpService {

    private val log = LoggerFactory.getLogger(PairingHttpService::class.java)
    private val gson = Gson()

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

    private fun getClientIp(ctx: ServiceRequestContext): String {
        return ctx.remoteAddress().address?.hostAddress ?: "unknown"
    }

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

    private fun jsonResponse(status: HttpStatus, data: Any): HttpResponse {
        return HttpResponse.of(status, MediaType.JSON_UTF_8, gson.toJson(data))
    }

    @Post("/api/pair/start")
    fun start(ctx: ServiceRequestContext): HttpResponse {
        val ip = getClientIp(ctx)
        if (isRateLimited(rateLimitStart, ip, START_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /start from IP {}", ip)
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                mapOf("error" to "Too many requests. Try again in a minute."))
        }

        val pendingCount = PairingService.countPendingCodes()
        if (pendingCount >= MAX_PENDING_CODES) {
            log.warn("Global pending code limit reached ({}) — rejecting /start from IP {}", pendingCount, ip)
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                mapOf("error" to "Too many active pairing requests. Try again later."))
        }

        val body = try {
            val raw = ctx.request().aggregate().join().contentUtf8()
            if (raw.isNotBlank()) JsonParser.parseString(raw).asJsonObject else null
        } catch (e: Exception) { null }

        val deviceName = body?.get("device_name")?.asString
            ?.replace(Regex("[\\x00-\\x1f\\x7f]"), "")
            ?.take(100)
            ?: ""

        val pairCode = PairingService.createPairCode(deviceName)
        val result = mutableMapOf<String, Any>(
            "code" to pairCode.code,
            "expires_in" to 300
        )
        val baseUrl = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.trimEnd('/')
        if (!baseUrl.isNullOrBlank()) {
            result["base_url"] = baseUrl
        }
        return jsonResponse(HttpStatus.OK, result)
    }

    @Get("/api/pair/status")
    fun status(ctx: ServiceRequestContext, @Param("code") @Default("") code: String): HttpResponse {
        val ip = getClientIp(ctx)
        if (isRateLimited(rateLimitStatus, ip, STATUS_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /status from IP {}", ip)
            return jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                mapOf("error" to "Too many requests. Slow down polling."))
        }

        if (code.isBlank()) {
            return jsonResponse(HttpStatus.BAD_REQUEST, mapOf("error" to "code parameter required"))
        }

        val pairStatus = PairingService.checkStatus(code)
        if (pairStatus == null) {
            return jsonResponse(HttpStatus.NOT_FOUND,
                mapOf("error" to "Pair code not found or expired"))
        }

        val result = mutableMapOf<String, Any?>("status" to pairStatus.status)
        if (pairStatus.status == "paired") {
            result["token"] = pairStatus.token
            result["username"] = pairStatus.username
            val baseUrl = AppConfig.findAll()
                .firstOrNull { it.config_key == "roku_base_url" }
                ?.config_val?.trimEnd('/')
            if (!baseUrl.isNullOrBlank()) {
                result["base_url"] = baseUrl
            }
        }
        return jsonResponse(HttpStatus.OK, result)
    }

    @Get("/api/pair/qr")
    fun qr(ctx: ServiceRequestContext, @Param("code") @Default("") code: String): HttpResponse {
        val ip = getClientIp(ctx)
        if (isRateLimited(rateLimitQr, ip, QR_MAX_PER_MINUTE)) {
            log.warn("Rate limit exceeded for /qr from IP {}", ip)
            return HttpResponse.of(HttpStatus.TOO_MANY_REQUESTS)
        }

        if (code.isBlank()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val configuredBase = AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.trimEnd('/')
        val baseUrl = if (!configuredBase.isNullOrBlank()) {
            configuredBase
        } else {
            val host = ctx.request().headers().get("host") ?: "localhost"
            "http://$host"
        }
        val pairUrl = "$baseUrl/pair?code=$code"

        val pngBytes = PairingService.generateQrCode(pairUrl)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.PNG)
            .contentLength(pngBytes.size.toLong())
            .add("Cache-Control", "no-cache")
            .build()
        return HttpResponse.of(headers, HttpData.wrap(pngBytes))
    }
}
