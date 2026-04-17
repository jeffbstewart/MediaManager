package net.stewart.mediamanager.armeria

import com.google.gson.GsonBuilder
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.logging.AppLogBuffer
import java.time.format.DateTimeFormatter

class AppLogHttpService {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Get("/admin/logs")
    fun logs(
        @Param("level") @Default("") level: String,
        @Param("logger") @Default("") logger: String,
        @Param("format") @Default("") format: String
    ): HttpResponse {
        var entries = when (level.uppercase()) {
            "ERROR" -> AppLogBuffer.getErrors().reversed()
            "WARN" -> (AppLogBuffer.getErrors() + AppLogBuffer.getWarnings())
                .sortedByDescending { it.timestamp }
            else -> AppLogBuffer.getAll()
        }

        if (logger.isNotEmpty()) {
            entries = entries.filter { it.loggerName.contains(logger, ignoreCase = true) }
        }

        return if (format == "json") respondJson(entries) else respondHtml(entries)
    }

    private fun respondJson(entries: List<AppLogBuffer.LogEntry>): HttpResponse {
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val jsonEntries = entries.map { e ->
            val map = mutableMapOf(
                "timestamp" to e.timestamp.format(fmt),
                "level" to e.level,
                "logger" to e.loggerName,
                "message" to e.message
            )
            if (e.stackTrace != null) map["stackTrace"] = e.stackTrace
            map
        }
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, gson.toJson(jsonEntries))
    }

    private fun respondHtml(entries: List<AppLogBuffer.LogEntry>): HttpResponse {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<title>Application Log</title>
<style>
body { background: #1a1a2e; color: #e0e0e0; font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; margin: 16px; }
h1 { color: #fff; font-size: 18px; margin-bottom: 8px; }
.controls { margin-bottom: 12px; color: #aaa; font-size: 12px; }
.controls a { color: #6ea8fe; text-decoration: none; margin-right: 16px; }
.controls a:hover { text-decoration: underline; }
table { border-collapse: collapse; width: 100%; }
th { background: #16213e; color: #aaa; text-align: left; padding: 6px 8px; font-weight: normal; text-transform: uppercase; font-size: 11px; position: sticky; top: 0; }
td { padding: 4px 8px; border-bottom: 1px solid #222; vertical-align: top; }
tr:hover { background: #16213e; }
.error { color: #f87171; }
.warn { color: #fbbf24; }
.info { color: #e0e0e0; }
.logger { color: #60a5fa; }
.msg { white-space: pre-wrap; word-break: break-all; max-width: 800px; }
details { margin-top: 4px; }
summary { color: #888; cursor: pointer; font-size: 12px; }
pre.stack { color: #f87171; font-size: 11px; margin: 4px 0 0 0; white-space: pre-wrap; max-height: 300px; overflow-y: auto; }
</style>
</head><body>
<h1>Application Log (${entries.size} entries)</h1>
<div class="controls">
<a href="/admin/logs">All</a>
<a href="/admin/logs?level=error">Errors</a>
<a href="/admin/logs?level=warn">Warn+Error</a>
<a href="/admin/logs?format=json">JSON</a>
| <a href="/admin/requests">Request Log</a>
</div>
<table>
<tr><th>Time</th><th>Level</th><th>Logger</th><th>Message</th></tr>
""")
        for (e in entries) {
            val levelClass = e.level.lowercase()
            val escapedMsg = escapeHtml(e.message)
            sb.append("<tr>")
            sb.append("<td>${e.timestamp.format(fmt)}</td>")
            sb.append("<td class=\"$levelClass\">${e.level}</td>")
            sb.append("<td class=\"logger\">${escapeHtml(e.loggerName)}</td>")
            sb.append("<td class=\"msg\">$escapedMsg")
            e.stackTrace?.let { trace ->
                sb.append("<details><summary>Stack trace</summary>")
                sb.append("<pre class=\"stack\">${escapeHtml(trace)}</pre>")
                sb.append("</details>")
            }
            sb.append("</td></tr>\n")
        }
        sb.append("</table></body></html>")
        return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8, sb.toString())
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
