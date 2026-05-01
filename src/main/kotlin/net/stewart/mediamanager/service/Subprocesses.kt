package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One-shot subprocess seam — same pattern as [Filesystems]. Production
 * forks a real OS process via [JdkSubprocessRunner]; tests substitute a
 * fake that records the argv and returns scripted exit code + stdout +
 * stderr.
 *
 * Scope is deliberately one-shot (run, wait for exit, read full stdout
 * + stderr). Long-lived processes that stream output line-by-line while
 * still alive (FFmpeg full transcode, go2rtc daemon, FFmpeg-as-HLS-relay)
 * are not in scope here — they need a different abstraction and stay on
 * direct `ProcessBuilder` for now.
 */
internal interface SubprocessRunner {
    fun run(
        command: List<String>,
        timeout: Duration = Duration.ofSeconds(30),
        redirectErrorStream: Boolean = false,
    ): SubprocessResult
}

/**
 * Result of a one-shot subprocess invocation.
 *
 * [stdout] always carries the merged output when `redirectErrorStream`
 * was true (matching what real-world ProcessBuilder does); [stderr] is
 * the empty string in that case.
 *
 * [timedOut] is `true` only when the wall-clock exceeded the supplied
 * timeout; in that case [exitCode] is `-1` and the process was forcibly
 * killed.
 */
internal data class SubprocessResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
)

/**
 * Single swap point. Reads [current] every call so a test can install a
 * fake without restarting anything. `internal` visibility keeps the
 * seam confined to this module (test code in the same module can swap;
 * external code never sees it). Production code should never write
 * [current] — only test infrastructure does, paired with a setup/teardown.
 */
internal object Subprocesses {
    @Volatile
    internal var current: SubprocessRunner = JdkSubprocessRunner
}

/**
 * Default ProcessBuilder-backed runner. Forks a real OS process,
 * captures stdout (and optionally stderr separately), waits for exit
 * up to [timeout], and force-kills on timeout.
 */
internal object JdkSubprocessRunner : SubprocessRunner {
    private val log = LoggerFactory.getLogger(JdkSubprocessRunner::class.java)

    override fun run(
        command: List<String>,
        timeout: Duration,
        redirectErrorStream: Boolean,
    ): SubprocessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        val process = pb.start()

        // Drain output streams in background threads so a chatty child
        // process doesn't block on a full pipe buffer while we wait.
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val stdoutThread = Thread({
            process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { stdoutBuf.append(it).append('\n') }
            }
        }, "subproc-stdout").apply { isDaemon = true; start() }
        val stderrThread = if (redirectErrorStream) null else Thread({
            process.errorStream.bufferedReader().use { r ->
                r.lineSequence().forEach { stderrBuf.append(it).append('\n') }
            }
        }, "subproc-stderr").apply { isDaemon = true; start() }

        val finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(2_000)
            stderrThread?.join(2_000)
            log.warn("Subprocess timed out after {}: {}", timeout, command.firstOrNull())
            return SubprocessResult(
                exitCode = -1,
                timedOut = true,
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
            )
        }
        // Process is done — give the drain threads a brief moment to
        // finish reading any tail bytes that hadn't yet been pumped.
        stdoutThread.join(2_000)
        stderrThread?.join(2_000)
        return SubprocessResult(
            exitCode = process.exitValue(),
            timedOut = false,
            stdout = stdoutBuf.toString(),
            stderr = stderrBuf.toString(),
        )
    }
}
