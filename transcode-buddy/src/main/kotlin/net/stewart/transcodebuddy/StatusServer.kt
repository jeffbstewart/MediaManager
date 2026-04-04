package net.stewart.transcodebuddy

import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * Lightweight HTTP status page for the transcode buddy.
 * Uses the JDK built-in HttpServer — no dependencies needed.
 * Auto-refreshes every 15 seconds via meta refresh header.
 */
class StatusServer(
    private val port: Int,
    private val config: BuddyConfig,
    private val grpcClient: BuddyGrpcClient,
    private val workerStatuses: List<WorkerStatus>
) {
    private val log = LoggerFactory.getLogger(StatusServer::class.java)
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext("/status") { exchange ->
            val html = buildStatusPage()
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "/status")
            exchange.sendResponseHeaders(302, -1)
        }
        s.executor = null
        s.start()
        server = s
        log.info("Status server started on http://localhost:{}/status", port)
    }

    fun stop() {
        server?.stop(0)
    }

    private fun buildStatusPage(): String {
        val connected = grpcClient.isConnected()
        val pending = grpcClient.lastPendingCount
        val uptime = Duration.between(startTime, Instant.now())

        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html><html><head>")
        sb.appendLine("<meta charset=\"utf-8\">")
        sb.appendLine("<meta http-equiv=\"refresh\" content=\"15\">")
        sb.appendLine("<title>Transcode Buddy — ${config.buddyName}</title>")
        sb.appendLine("<style>")
        sb.appendLine("body { font-family: system-ui, sans-serif; background: #1a1a1a; color: #e0e0e0; padding: 2rem; max-width: 900px; margin: 0 auto; }")
        sb.appendLine("h1 { color: #90caf9; margin-bottom: 0.25rem; }")
        sb.appendLine("h2 { color: #90caf9; margin-top: 2rem; }")
        sb.appendLine(".subtitle { color: #999; margin-bottom: 2rem; }")
        sb.appendLine("table { border-collapse: collapse; width: 100%; }")
        sb.appendLine("th, td { text-align: left; padding: 0.5rem 1rem; border-bottom: 1px solid #333; }")
        sb.appendLine("th { color: #999; font-weight: 500; }")
        sb.appendLine(".ok { color: #4caf50; } .err { color: #f44336; } .warn { color: #ff9800; }")
        sb.appendLine(".idle { color: #999; }")
        sb.appendLine(".mono { font-family: monospace; }")
        sb.appendLine(".rate { color: #90caf9; }")
        sb.appendLine("</style></head><body>")

        sb.appendLine("<h1>Transcode Buddy</h1>")
        sb.appendLine("<div class=\"subtitle\">${config.buddyName} &mdash; uptime ${formatDuration(uptime)}</div>")

        // Connection status
        sb.appendLine("<h2>Connection</h2>")
        sb.appendLine("<table>")
        sb.appendLine("<tr><th>Server</th><td>${config.serverUrl}</td></tr>")
        sb.appendLine("<tr><th>Status</th><td class=\"${if (connected) "ok" else "err"}\">${if (connected) "Connected" else "Disconnected"}</td></tr>")
        sb.appendLine("<tr><th>Pending Work</th><td>$pending leases</td></tr>")
        sb.appendLine("<tr><th>Encoder</th><td>${config.encoderPreference}</td></tr>")
        sb.appendLine("<tr><th>Workers</th><td>${config.workerCount}</td></tr>")
        sb.appendLine("</table>")

        // Worker status
        sb.appendLine("<h2>Workers</h2>")
        sb.appendLine("<table>")
        sb.appendLine("<tr><th>Worker</th><th>State</th><th>Task</th><th>File</th><th>Output Size</th><th>Rate</th><th>Progress</th><th>ETA</th></tr>")

        for (ws in workerStatuses) {
            val snapshot = ws.snapshot()
            val stateClass = when (snapshot.state) {
                "idle" -> "idle"
                "error" -> "err"
                else -> "ok"
            }
            sb.appendLine("<tr>")
            sb.appendLine("<td>Worker-${ws.index}</td>")
            sb.appendLine("<td class=\"$stateClass\">${snapshot.state}</td>")
            sb.appendLine("<td>${snapshot.task}</td>")
            sb.appendLine("<td>${snapshot.fileName}</td>")
            sb.appendLine("<td class=\"mono\">${snapshot.outputSize}</td>")
            sb.appendLine("<td class=\"rate\">${snapshot.rate}</td>")
            sb.appendLine("<td>${snapshot.progress}</td>")
            sb.appendLine("<td>${snapshot.eta}</td>")
            sb.appendLine("</tr>")
        }
        sb.appendLine("</table>")

        // Local cache
        if (config.localTempDir != null) {
            val tempDir = File(config.localTempDir)
            if (tempDir.exists()) {
                val files = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()
                val totalSize = files.sumOf { it.length() }
                sb.appendLine("<h2>Local Cache</h2>")
                sb.appendLine("<table>")
                sb.appendLine("<tr><th>Path</th><td>${tempDir.absolutePath}</td></tr>")
                sb.appendLine("<tr><th>Files</th><td>${files.size}</td></tr>")
                sb.appendLine("<tr><th>Total Size</th><td>${humanSize(totalSize)}</td></tr>")
                sb.appendLine("</table>")
            }
        }

        // Recent completions
        val history = workerStatuses.flatMap { it.recentCompletions() }
            .sortedByDescending { it.completedAt }
            .take(5)
        if (history.isNotEmpty()) {
            sb.appendLine("<h2>Recent Completions</h2>")
            sb.appendLine("<table>")
            sb.appendLine("<tr><th>File</th><th>Task</th><th>Result</th><th>Duration</th><th>Output Size</th><th>When</th></tr>")
            for (entry in history) {
                val resultClass = if (entry.result == "success") "ok" else "err"
                val ago = Duration.between(entry.completedAt, Instant.now())
                sb.appendLine("<tr>")
                sb.appendLine("<td>${entry.fileName}</td>")
                sb.appendLine("<td>${entry.task}</td>")
                sb.appendLine("<td class=\"$resultClass\">${entry.result}</td>")
                sb.appendLine("<td>${formatDuration(Duration.ofSeconds(entry.durationSeconds))}</td>")
                sb.appendLine("<td class=\"mono\">${if (entry.outputBytes > 0) humanSize(entry.outputBytes) else ""}</td>")
                sb.appendLine("<td>${formatDuration(ago)} ago</td>")
                sb.appendLine("</tr>")
            }
            sb.appendLine("</table>")
        }

        sb.appendLine("<div class=\"subtitle\" style=\"margin-top:2rem;\">Auto-refreshes every 15 seconds</div>")
        sb.appendLine("</body></html>")
        return sb.toString()
    }

    companion object {
        val startTime: Instant = Instant.now()

        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            else -> "%.2fGB".format(bytes / (1024.0 * 1024 * 1024))
        }

        fun formatDuration(d: Duration): String {
            val h = d.toHours()
            val m = d.toMinutesPart()
            val s = d.toSecondsPart()
            return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
        }
    }
}

/**
 * Mutable status tracker for a single worker. Updated by TranscodeWorker,
 * read by StatusServer. Thread-safe via volatile fields.
 */
class WorkerStatus(val index: Int) {
    @Volatile var state: String = "idle"
    @Volatile var task: String = ""
    @Volatile var fileName: String = ""
    @Volatile var outputFile: File? = null
    /** Expected final size of the output (source file size for staging, 0 if unknown). */
    @Volatile var expectedSize: Long = 0
    /** Transcode progress 0-100, reported by FFmpeg output parsing. */
    @Volatile var transcodePercent: Int = 0
    /** When the current task started (for ETA calculation from percent). */
    @Volatile var taskStartTime: Long = 0

    // Completion history (ring buffer, most recent 5)
    private val completions = java.util.concurrent.ConcurrentLinkedDeque<CompletionEntry>()

    fun recordCompletion(fileName: String, task: String, result: String, durationSeconds: Long, outputBytes: Long) {
        completions.addFirst(CompletionEntry(fileName, task, result, durationSeconds, outputBytes, Instant.now()))
        while (completions.size > 10) completions.removeLast()
    }

    fun recentCompletions(): List<CompletionEntry> = completions.toList()

    data class CompletionEntry(
        val fileName: String,
        val task: String,
        val result: String,
        val durationSeconds: Long,
        val outputBytes: Long,
        val completedAt: Instant
    )
    @Volatile private var lastSize: Long = 0
    @Volatile private var lastSizeTime: Long = System.currentTimeMillis()
    @Volatile private var bytesPerSecond: Double = 0.0

    fun snapshot(): Snapshot {
        val file = outputFile
        val currentSize = file?.let { if (it.exists()) it.length() else 0L } ?: 0L
        val now = System.currentTimeMillis()
        val elapsed = (now - lastSizeTime) / 1000.0

        if (elapsed > 5 && currentSize > lastSize) {
            bytesPerSecond = (currentSize - lastSize) / elapsed
            lastSize = currentSize
            lastSizeTime = now
        } else if (file == null || !file.exists()) {
            bytesPerSecond = 0.0
            lastSize = 0
            lastSizeTime = now
        }

        val pct = transcodePercent
        // Progress: staging uses file size ratio, transcodes use FFmpeg-reported percent
        val progress = when {
            expectedSize > 0 && currentSize > 0 -> "${(currentSize * 100 / expectedSize)}%"
            pct > 0 -> "$pct%"
            else -> ""
        }

        // ETA: staging from bytes remaining / rate, transcodes from percent + elapsed time
        val eta = when {
            bytesPerSecond > 0 && expectedSize > 0 && currentSize < expectedSize -> {
                val remainingSec = ((expectedSize - currentSize) / bytesPerSecond).toLong()
                StatusServer.formatDuration(Duration.ofSeconds(remainingSec))
            }
            pct in 1..99 && taskStartTime > 0 -> {
                val elapsedSec = (now - taskStartTime) / 1000.0
                val remainingSec = (elapsedSec * (100 - pct) / pct).toLong()
                StatusServer.formatDuration(Duration.ofSeconds(remainingSec))
            }
            else -> ""
        }

        return Snapshot(
            state = state,
            task = task,
            fileName = fileName,
            outputSize = if (file != null && currentSize > 0) StatusServer.humanSize(currentSize) else "",
            rate = if (bytesPerSecond > 0) "${StatusServer.humanSize(bytesPerSecond.toLong())}/s" else "",
            progress = progress,
            eta = eta
        )
    }

    data class Snapshot(
        val state: String,
        val task: String,
        val fileName: String,
        val outputSize: String,
        val rate: String,
        val progress: String,
        val eta: String
    )
}
