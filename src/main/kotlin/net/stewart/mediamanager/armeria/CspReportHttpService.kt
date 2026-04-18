package net.stewart.mediamanager.armeria

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Post
import org.slf4j.LoggerFactory

/**
 * Collects browser-sent Content-Security-Policy violation reports and
 * logs them so we can see them. Flows through the `csp.violation` SLF4J
 * logger → [net.stewart.logging.BinnacleExporter] → Binnacle, queryable
 * by `code.namespace = csp.violation`.
 *
 * Accepts both CSP report formats in the wild:
 * - Legacy `Content-Type: application/csp-report`
 *   `{"csp-report": { "document-uri": "...", "violated-directive": "...", ... }}`
 * - Modern `Content-Type: application/reports+json`
 *   `[{ "type": "csp-violation", "body": { "documentURL": "...", "effectiveDirective": "...", ... }}, ...]`
 *
 * Intentionally unauthenticated: violations can fire on the login page
 * and during the auth flow. Abuse posture: returns 204 regardless of
 * body validity (don't leak parse state back to the browser) and
 * enforces [MAX_BODY_BYTES] so the log pipeline can't be flooded by a
 * single malicious report. Rate-limiting per source IP is a later
 * exercise if it's ever needed.
 *
 * Registered via `blockingNoAuth(...)` — see [ArmeriaServer].
 */
@Blocking
class CspReportHttpService {

    private val log = LoggerFactory.getLogger("csp.violation")
    private val mapper = ObjectMapper()

    @Post("/csp-report")
    fun report(ctx: ServiceRequestContext): HttpResponse {
        val aggregated = ctx.request().aggregate().join()
        val body = aggregated.content().array()
        if (body.size > MAX_BODY_BYTES) {
            // Too large — don't bother parsing, don't log, just refuse.
            return HttpResponse.of(HttpStatus.BAD_REQUEST)
        }

        val contentType = ctx.request().headers().contentType()?.toString()?.lowercase().orEmpty()
        val userAgent = ctx.request().headers().get("user-agent")?.take(512) ?: "-"

        try {
            val root = mapper.readTree(body)
            when {
                contentType.contains("reports+json") || root.isArray -> {
                    // Modern: array of reports, each with a nested `body`.
                    for (entry in root) {
                        val kind = entry.path("type").asText()
                        if (kind != "csp-violation") continue
                        logViolation(ModernReport(entry.path("body")), userAgent)
                    }
                }
                else -> {
                    // Legacy: `{"csp-report": {...}}` object form.
                    val inner = root.path("csp-report")
                    if (inner.isObject) logViolation(LegacyReport(inner), userAgent)
                }
            }
        } catch (e: Exception) {
            log.warn("CSP report parse failed: {} (body size={})", e.message, body.size)
        }

        // Browsers don't care about the status as long as we return a
        // 2xx-ish. 204 keeps logs clean of a body-bearing response.
        return HttpResponse.of(HttpStatus.NO_CONTENT)
    }

    private fun logViolation(report: ReportView, userAgent: String) {
        log.warn(
            "CSP violation: directive={} blocked={} document={} disposition={} source={}:{}:{} ua='{}'",
            report.directive,
            report.blocked,
            report.document,
            report.disposition,
            report.sourceFile,
            report.line,
            report.column,
            userAgent
        )
    }

    // ---- Report shape views ------------------------------------------------

    private interface ReportView {
        val directive: String
        val blocked: String
        val document: String
        val disposition: String
        val sourceFile: String
        val line: String
        val column: String
    }

    private class LegacyReport(private val n: JsonNode) : ReportView {
        override val directive get() = n.textOr("effective-directive", "violated-directive")
        override val blocked get() = n.textOr("blocked-uri")
        override val document get() = n.textOr("document-uri")
        override val disposition get() = n.textOr("disposition")
        override val sourceFile get() = n.textOr("source-file")
        override val line get() = n.textOr("line-number")
        override val column get() = n.textOr("column-number")
    }

    private class ModernReport(private val n: JsonNode) : ReportView {
        override val directive get() = n.textOr("effectiveDirective")
        override val blocked get() = n.textOr("blockedURL")
        override val document get() = n.textOr("documentURL")
        override val disposition get() = n.textOr("disposition")
        override val sourceFile get() = n.textOr("sourceFile")
        override val line get() = n.textOr("lineNumber")
        override val column get() = n.textOr("columnNumber")
    }

    companion object {
        /** Real reports are ~1 KB; 8 KB is generous but forecloses abuse. */
        private const val MAX_BODY_BYTES = 8 * 1024
    }
}

private fun JsonNode.textOr(vararg fields: String): String {
    for (f in fields) {
        val node = this.get(f) ?: continue
        if (node.isNull) continue
        val text = if (node.isTextual) node.asText() else node.toString()
        if (text.isNotBlank() && text != "null") return text.take(512)
    }
    return "-"
}
