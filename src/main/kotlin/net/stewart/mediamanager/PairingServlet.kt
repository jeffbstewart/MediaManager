package net.stewart.mediamanager

import com.google.gson.Gson
import com.google.gson.JsonParser
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory

/**
 * REST API for device pairing (QR code flow).
 *
 * All endpoints are unauthenticated — the pairing flow itself is the auth mechanism.
 * The pair code acts as a short-lived capability token.
 *
 * POST /api/pair/start     — create a new pair code (device calls this)
 * GET  /api/pair/status     — poll for pairing completion (device polls this)
 * GET  /api/pair/qr         — get QR code PNG for a pair code
 */
@WebServlet(urlPatterns = ["/api/pair/*"])
class PairingServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(PairingServlet::class.java)
    private val gson = Gson()

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
        val body = try {
            val raw = req.inputStream.readNBytes(4096).toString(Charsets.UTF_8)
            if (raw.isNotBlank()) JsonParser.parseString(raw).asJsonObject else null
        } catch (e: Exception) { null }

        val deviceName = body?.get("device_name")?.asString ?: ""

        val pairCode = PairingService.createPairCode(deviceName)
        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "code" to pairCode.code,
            "expires_in" to 300
        ))
    }

    private fun handleStatus(req: HttpServletRequest, resp: HttpServletResponse) {
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
        }
        sendJson(resp, HttpServletResponse.SC_OK, result)
    }

    private fun handleQr(req: HttpServletRequest, resp: HttpServletResponse) {
        val code = req.getParameter("code")
        if (code.isNullOrBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "code parameter required")
            return
        }

        // Build the pairing URL from the request
        val scheme = req.getHeader("X-Forwarded-Proto") ?: req.scheme
        val host = req.getHeader("X-Forwarded-Host") ?: req.getHeader("Host") ?: "${req.serverName}:${req.serverPort}"
        val baseUrl = "$scheme://$host"
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
