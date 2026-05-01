package net.stewart.mediamanager.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory test double for [StreamingProcess].
 *
 * Stdout / stderr are pre-buffered: the test scripts the bytes the
 * production code will read; [java.io.ByteArrayInputStream] makes
 * `bufferedReader().forEachLine { ... }` deterministic and non-blocking.
 *
 * Lifecycle is controlled by the test:
 *   - The process is "alive" from construction until [simulateExit] is
 *     called (or [destroyForcibly] flips it dead).
 *   - [waitFor] blocks the calling thread until the test calls
 *     [simulateExit] or [destroyForcibly]. In tests that fully consume
 *     stdout before waiting, calling [simulateExit] up front (via the
 *     `initialExitCode` constructor arg) lets the whole flow run on a
 *     single thread without blocking.
 *
 * A test can either:
 *   - Construct with `initialExitCode = 0` and pre-baked stdout — the
 *     happy "ffmpeg already finished" shape; production reads stdout
 *     to EOF, calls waitFor, gets 0 immediately.
 *   - Construct with `initialExitCode = null` (still alive) and call
 *     [simulateExit] / [destroyForcibly] from the test thread to
 *     transition the lifecycle. Useful for daemon-shaped tests
 *     (Go2rtc, LiveTV).
 */
internal class FakeStreamingProcess(
    stdoutContent: String = "",
    stderrContent: String = "",
    initialExitCode: Int? = null,
    /**
     * Synthetic pid surfaced via [pid]. Defaults to a unique non-zero
     * value per fake instance so tests can assert pid-keyed bookkeeping.
     */
    override val pid: Long = nextSyntheticPid(),
) : StreamingProcess {

    override val stdout: InputStream = ByteArrayInputStream(stdoutContent.toByteArray())
    override val stderr: InputStream = ByteArrayInputStream(stderrContent.toByteArray())

    /**
     * Exposed so tests that want to assert "production never tried to
     * write to stdin" can read [stdinSink].
     */
    val stdinSink: ByteArrayOutputStream = ByteArrayOutputStream()
    override val stdin: OutputStream = stdinSink

    private val exitLock = ReentrantLock()
    private val exitCondition = exitLock.newCondition()

    /** null while still alive; set by [simulateExit] or [destroyForcibly]. */
    private var exitCodeOrNull: Int? = initialExitCode
    private val destroyedForciblyFlag = AtomicBoolean(false)
    private val destroyCalled = AtomicBoolean(false)

    /** Has [destroy] been invoked? Inspect from tests. */
    val destroyInvoked: Boolean get() = destroyCalled.get()

    /** Has [destroyForcibly] been invoked? Inspect from tests. */
    val destroyForciblyInvoked: Boolean get() = destroyedForciblyFlag.get()

    override val isAlive: Boolean
        get() = exitLock.withLock { exitCodeOrNull == null && !destroyedForciblyFlag.get() }

    override fun waitFor(): Int {
        exitLock.withLock {
            while (exitCodeOrNull == null && !destroyedForciblyFlag.get()) {
                exitCondition.await()
            }
            return exitCodeOrNull ?: -1  // -1 mirrors a force-killed process
        }
    }

    override fun waitFor(timeout: Duration): Boolean {
        exitLock.withLock {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (exitCodeOrNull == null && !destroyedForciblyFlag.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                exitCondition.awaitNanos(remaining)
            }
            return true
        }
    }

    override fun exitValue(): Int {
        exitLock.withLock {
            return exitCodeOrNull
                ?: throw IllegalThreadStateException("FakeStreamingProcess has not exited")
        }
    }

    override fun destroy() {
        destroyCalled.set(true)
    }

    override fun destroyForcibly() {
        exitLock.withLock {
            destroyedForciblyFlag.set(true)
            exitCondition.signalAll()
        }
    }

    /**
     * Test-side trigger: flip the process to "exited with [exitCode]"
     * and unblock anyone waiting in [waitFor]. Idempotent — first call
     * wins, mirroring how a real process can only exit once.
     */
    fun simulateExit(exitCode: Int) {
        exitLock.withLock {
            if (exitCodeOrNull == null) {
                exitCodeOrNull = exitCode
                exitCondition.signalAll()
            }
        }
    }

    companion object {
        private val pidGen = java.util.concurrent.atomic.AtomicLong(900_000)
        private fun nextSyntheticPid(): Long = pidGen.incrementAndGet()
    }
}
