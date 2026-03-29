package net.stewart.mediamanager.armeria

import com.google.gson.GsonBuilder
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.service.RequestLogBuffer
import java.time.format.DateTimeFormatter

class RequestLogHttpService {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Get("/admin/requests")
    fun requests(
        @Param("ua") @Default("") ua: String,
        @Param("path") @Default("") path: String,
        @Param("status") @Default("") status: String,
        @Param("format") @Default("") format: String
    ): HttpResponse {
        var entries = RequestLogBuffer.getAll().reversed()

        if (ua.isNotEmpty()) {
            entries = entries.filter { it.userAgent.contains(ua, ignoreCase = true) }
        }
        if (path.isNotEmpty()) {
            entries = entries.filter { it.uri.startsWith(path) }
        }
        if (status.isNotEmpty()) {
            entries = filterByStatus(entries, status)
        }

        return if (format == "json") respondJson(entries) else respondHtml(entries)
    }

    private fun filterByStatus(
        entries: List<RequestLogBuffer.RequestLogEntry>,
        filter: String
    ): List<RequestLogBuffer.RequestLogEntry> {
        if (filter.endsWith("xx")) {
            val prefix = filter[0].digitToIntOrNull() ?: return entries
            return entries.filter { it.status / 100 == prefix }
        }
        val code = filter.toIntOrNull() ?: return entries
        return entries.filter { it.status == code }
    }

    private fun respondJson(entries: List<RequestLogBuffer.RequestLogEntry>): HttpResponse {
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val jsonEntries = entries.map { e ->
            mapOf(
                "timestamp" to e.timestamp.format(fmt),
                "clientIp" to e.clientIp,
                "username" to e.username,
                "method" to e.method,
                "uri" to e.uri,
                "protocol" to e.protocol,
                "status" to e.status,
                "responseSize" to e.responseSize,
                "userAgent" to e.userAgent,
                "elapsedMs" to e.elapsedMs
            )
        }
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, gson.toJson(jsonEntries))
    }

    private fun respondHtml(entries: List<RequestLogBuffer.RequestLogEntry>): HttpResponse {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<title>Request Log</title>
<style>
body { background: #1a1a2e; color: #e0e0e0; font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; margin: 16px; }
h1 { color: #fff; font-size: 18px; margin-bottom: 8px; }
.controls { margin-bottom: 12px; color: #aaa; font-size: 12px; }
.controls a { color: #6ea8fe; text-decoration: none; margin-right: 16px; }
.controls a:hover { text-decoration: underline; }
table { border-collapse: collapse; width: 100%; }
th { background: #16213e; color: #aaa; text-align: left; padding: 6px 8px; font-weight: normal; text-transform: uppercase; font-size: 11px; position: sticky; top: 0; }
td { padding: 4px 8px; border-bottom: 1px solid #222; white-space: nowrap; }
tr:hover { background: #16213e; }
.s2 { color: #4ade80; } .s3 { color: #60a5fa; } .s4 { color: #fbbf24; } .s5 { color: #f87171; }
.method { color: #c084fc; }
.slow { color: #fbbf24; }
.ua { max-width: 250px; overflow: hidden; text-overflow: ellipsis; }
</style>
</head><body>
<h1>Request Log (${entries.size} entries)</h1>
<div class="controls">
<a href="/admin/requests">All</a>
<a href="/admin/requests?status=4xx">4xx</a>
<a href="/admin/requests?status=5xx">5xx</a>
<a href="/admin/requests?path=/roku">Roku</a>
<a href="/admin/requests?path=/buddy">Buddy</a>
<a href="/admin/requests?format=json">JSON</a>
</div>
<table>
<tr><th>Time</th><th>IP</th><th>User</th><th>Method</th><th>URI</th><th>Status</th><th>Size</th><th>Ms</th><th>User-Agent</th></tr>
""")
        for (e in entries) {
            val statusClass = "s${e.status / 100}"
            val sizeStr = if (e.responseSize > 0) formatSize(e.responseSize) else "-"
            val msClass = if (e.elapsedMs > 1000) "slow" else ""
            val escapedUri = escapeHtml(e.uri)
            val escapedUa = escapeHtml(e.userAgent)
            sb.append("<tr>")
            sb.append("<td>${e.timestamp.format(fmt)}</td>")
            sb.append("<td>${e.clientIp}</td>")
            sb.append("<td>${e.username}</td>")
            sb.append("<td class=\"method\">${e.method}</td>")
            sb.append("<td>$escapedUri</td>")
            sb.append("<td class=\"$statusClass\">${e.status}</td>")
            sb.append("<td>$sizeStr</td>")
            sb.append("<td class=\"$msClass\">${e.elapsedMs}</td>")
            sb.append("<td class=\"ua\" title=\"$escapedUa\">$escapedUa</td>")
            sb.append("</tr>\n")
        }
        sb.append("</table></body></html>")
        return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8, sb.toString())
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
