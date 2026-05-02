package net.stewart.transcode

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Cross-module subprocess seam — production forks a real OS process via
 * [JdkSubprocessRunner]; tests substitute a fake that records the argv and
 * returns scripted exit code + stdout + stderr.
 *
 * Lives in transcode-common so both the main MediaManager module and the
 * shared transcode helpers (VideoProbe, ThumbnailSpriteGenerator, etc.)
 * route through the same swap point. Visibility is `public` because Kotlin's
 * `internal` is module-scoped — the seam is still effectively project-only:
 * not part of any published API.
 */
public interface SubprocessRunner {
    public fun run(
        command: List<String>,
        timeout: Duration = Duration.ofSeconds(30),
        redirectErrorStream: Boolean = false,
    ): SubprocessResult

    /**
     * Spawn a long-lived subprocess and hand back a [StreamingProcess]
     * the caller drives via line-by-line reads on [StreamingProcess.stdout]
     * (or the merged stdout when `redirectErrorStream` is true), polling
     * [StreamingProcess.isAlive], waiting via [StreamingProcess.waitFor],
     * and shutting down via [StreamingProcess.destroyForcibly].
     *
     * This is the FFmpeg-transcode / go2rtc-daemon / FFmpeg-HLS shape:
     * the caller wants a handle that's *running*, not a finished result.
     * For one-shot calls (run-and-collect), prefer [run].
     */
    public fun start(
        command: List<String>,
        redirectErrorStream: Boolean = false,
        workingDir: java.nio.file.Path? = null,
    ): StreamingProcess
}

/**
 * Mirror of [java.lang.Process] under our control, so the [Subprocesses.current]
 * fake can hand callers a fully scriptable handle without ever forking
 * a real OS process.
 */
public interface StreamingProcess {
    /** True until the process has exited or been destroyed. */
    public val isAlive: Boolean

    /**
     * OS process id. The JDK runner returns the real pid; the fake
     * returns a synthetic value that's stable for the lifetime of the
     * fake instance (so tests can assert pid-based bookkeeping).
     */
    public val pid: Long

    /**
     * Stdout of the spawned process. When started with
     * `redirectErrorStream = true` this carries the merged output
     * (stdout + stderr), matching what `Process.getInputStream()` does.
     */
    public val stdout: java.io.InputStream

    /** Stderr of the spawned process. */
    public val stderr: java.io.InputStream

    /** Stdin of the spawned process. */
    public val stdin: java.io.OutputStream

    /** Block until the process exits and return its exit code. */
    public fun waitFor(): Int

    /** Block up to [timeout] for the process to exit. Returns true when exited within the window; false on timeout. */
    public fun waitFor(timeout: Duration): Boolean

    /** The exit code. Throws [IllegalThreadStateException] while alive. */
    public fun exitValue(): Int

    /** Polite request to terminate. */
    public fun destroy()

    /** Force-kill the process. Used by shutdown paths. */
    public fun destroyForcibly()
}

/**
 * Result of a one-shot subprocess invocation.
 *
 * [stdout] always carries the merged output when `redirectErrorStream`
 * was true; [stderr] is the empty string in that case.
 *
 * [timedOut] is `true` only when the wall-clock exceeded the supplied
 * timeout; in that case [exitCode] is `-1` and the process was forcibly
 * killed.
 */
public data class SubprocessResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
)

/**
 * Single swap point. Reads [current] every call so a test can install a
 * fake without restarting anything.
 */
public object Subprocesses {
    @Volatile
    public var current: SubprocessRunner = JdkSubprocessRunner
}

/**
 * Production [StreamingProcess] — a thin pass-through to [java.lang.Process].
 */
public class JdkStreamingProcess(private val process: Process) : StreamingProcess {
    override val isAlive: Boolean get() = process.isAlive
    override val pid: Long get() = process.pid()
    override val stdout: java.io.InputStream get() = process.inputStream
    override val stderr: java.io.InputStream get() = process.errorStream
    override val stdin: java.io.OutputStream get() = process.outputStream
    override fun waitFor(): Int = process.waitFor()
    override fun waitFor(timeout: Duration): Boolean =
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
    override fun exitValue(): Int = process.exitValue()
    override fun destroy() { process.destroy() }
    override fun destroyForcibly() { process.destroyForcibly() }
}

/**
 * Default ProcessBuilder-backed runner. Forks a real OS process,
 * captures stdout (and optionally stderr separately), waits for exit
 * up to [timeout], and force-kills on timeout.
 */
public object JdkSubprocessRunner : SubprocessRunner {
    private val log = LoggerFactory.getLogger(JdkSubprocessRunner::class.java)

    override fun start(
        command: List<String>,
        redirectErrorStream: Boolean,
        workingDir: java.nio.file.Path?,
    ): StreamingProcess {
        val pb = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        if (workingDir != null) pb.directory(workingDir.toFile())
        return JdkStreamingProcess(pb.start())
    }

    override fun run(
        command: List<String>,
        timeout: Duration,
        redirectErrorStream: Boolean,
    ): SubprocessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        val process = pb.start()

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
