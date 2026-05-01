package net.stewart.mediamanager.service

import org.junit.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct lifecycle + content checks on [FakeStreamingProcess]. These
 * lock down the contract so production migrations can rely on the fake
 * behaving like `Process`.
 */
internal class FakeStreamingProcessTest {

    @Test
    fun `pre-baked stdout reads cleanly to EOF`() {
        val proc = FakeStreamingProcess(
            stdoutContent = "line1\nline2\nline3\n",
            initialExitCode = 0,
        )
        val seen = mutableListOf<String>()
        proc.stdout.bufferedReader().forEachLine { seen += it }
        assertEquals(listOf("line1", "line2", "line3"), seen)
    }

    @Test
    fun `stderr is independent of stdout`() {
        val proc = FakeStreamingProcess(
            stdoutContent = "out\n",
            stderrContent = "err\n",
            initialExitCode = 0,
        )
        assertEquals("out", proc.stdout.bufferedReader().readLine())
        assertEquals("err", proc.stderr.bufferedReader().readLine())
    }

    @Test
    fun `isAlive flips false when initialExitCode is set`() {
        val alive = FakeStreamingProcess()
        assertTrue(alive.isAlive)

        val exited = FakeStreamingProcess(initialExitCode = 0)
        assertFalse(exited.isAlive)
    }

    @Test
    fun `waitFor returns the simulated exit code`() {
        val proc = FakeStreamingProcess(initialExitCode = 42)
        assertEquals(42, proc.waitFor())
        assertEquals(42, proc.exitValue())
    }

    @Test
    fun `waitFor blocks until simulateExit is called from another thread`() {
        val proc = FakeStreamingProcess()
        val exitor = Thread {
            Thread.sleep(50)
            proc.simulateExit(7)
        }
        exitor.start()
        val code = proc.waitFor()
        exitor.join()
        assertEquals(7, code)
    }

    @Test
    fun `bounded waitFor returns false on timeout while still alive`() {
        val proc = FakeStreamingProcess()
        val finished = proc.waitFor(Duration.ofMillis(50))
        assertFalse(finished)
        assertTrue(proc.isAlive, "process is still alive after a missed wait")
    }

    @Test
    fun `bounded waitFor returns true after simulateExit unblocks it`() {
        val proc = FakeStreamingProcess()
        proc.simulateExit(0)
        assertTrue(proc.waitFor(Duration.ofMillis(50)))
        assertEquals(0, proc.exitValue())
    }

    @Test
    fun `exitValue throws while still alive`() {
        val proc = FakeStreamingProcess()
        assertFails { proc.exitValue() }
    }

    @Test
    fun `destroyForcibly flips isAlive false and unblocks waitFor`() {
        val proc = FakeStreamingProcess()
        val waiter = Thread {
            // Returns -1 when destroyed without an exit code set.
            assertEquals(-1, proc.waitFor())
        }
        waiter.start()
        proc.destroyForcibly()
        waiter.join(2_000)
        assertFalse(proc.isAlive)
        assertTrue(proc.destroyForciblyInvoked)
    }

    @Test
    fun `destroy is recorded but does not change liveness on its own`() {
        val proc = FakeStreamingProcess()
        proc.destroy()
        assertTrue(proc.destroyInvoked)
        // Polite destroy is just bookkeeping for the fake — production
        // code that calls .destroy() on a real Process gets best-effort
        // termination; the fake doesn't simulate that pessimistically.
        assertTrue(proc.isAlive)
    }

    @Test
    fun `simulateExit is idempotent — first code wins`() {
        val proc = FakeStreamingProcess()
        proc.simulateExit(0)
        proc.simulateExit(99)
        assertEquals(0, proc.exitValue())
    }

    @Test
    fun `synthetic pids are unique per fake instance`() {
        val a = FakeStreamingProcess()
        val b = FakeStreamingProcess()
        assertNotEquals(a.pid, b.pid)
    }
}
