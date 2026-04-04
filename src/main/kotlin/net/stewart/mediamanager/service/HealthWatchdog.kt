package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI

/**
 * Daemon thread that periodically probes the main Armeria port's /health endpoint.
 * If the probe times out, dumps all JVM thread stacks to the log.
 *
 * This catches situations where the main listener is wedged (stuck event loop threads,
 * connection backlog exhaustion, etc.) while the internal monitoring port still responds.
 * The thread dump reveals exactly what each thread is doing at the time of the hang.
 */
class HealthWatchdog(private val mainPort: Int) {

    private val log = LoggerFactory.getLogger(HealthWatchdog::class.java)
    private val probeIntervalMs = 30_000L
    private val probeTimeoutMs = 5_000
    @Volatile private var running = true
    @Volatile private var inFailure = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({
            // Give the server time to start before probing
            Thread.sleep(60_000)
            log.info("Health watchdog started — probing localhost:{} every {}s", mainPort, probeIntervalMs / 1000)

            while (running) {
                try {
                    Thread.sleep(probeIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }

                if (!running) break

                val healthy = probeHealth()
                if (!healthy && !inFailure) {
                    // First failure — dump threads
                    inFailure = true
                    log.error("WATCHDOG: Main port {} is NOT responding. Dumping all threads.", mainPort)
                    dumpAllThreads()
                } else if (!healthy && inFailure) {
                    // Continued failure — log but don't spam thread dumps
                    log.error("WATCHDOG: Main port {} still not responding.", mainPort)
                } else if (healthy && inFailure) {
                    // Recovery
                    log.info("WATCHDOG: Main port {} recovered.", mainPort)
                    inFailure = false
                }
            }
        }, "health-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun probeHealth(): Boolean {
        return try {
            val url = URI("http://localhost:$mainPort/health").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = probeTimeoutMs
            conn.readTimeout = probeTimeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun dumpAllThreads() {
        val sb = StringBuilder()
        sb.appendLine("=== THREAD DUMP (${Thread.activeCount()} threads) ===")

        val stacks = Thread.getAllStackTraces()
        // Sort: BLOCKED first, then RUNNABLE, then others
        val sorted = stacks.entries.sortedWith(compareBy<Map.Entry<Thread, Array<StackTraceElement>>> {
            when (it.key.state) {
                Thread.State.BLOCKED -> 0
                Thread.State.RUNNABLE -> 1
                Thread.State.WAITING -> 2
                Thread.State.TIMED_WAITING -> 3
                else -> 4
            }
        }.thenBy { it.key.name })

        for ((thread, stack) in sorted) {
            sb.appendLine()
            sb.appendLine("\"${thread.name}\" #${thread.id} ${thread.state}" +
                if (thread.isDaemon) " daemon" else "" +
                if (thread == Thread.currentThread()) " [WATCHDOG]" else "")
            for (frame in stack) {
                sb.appendLine("    at $frame")
            }
        }
        sb.appendLine("=== END THREAD DUMP ===")
        log.error(sb.toString())
    }
}
