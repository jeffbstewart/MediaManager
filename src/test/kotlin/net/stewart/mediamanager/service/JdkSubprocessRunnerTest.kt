package net.stewart.mediamanager.service

import net.stewart.transcode.JdkSubprocessRunner
import org.junit.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke tests against the real JDK runner — proves the production seam
 * works end-to-end against actual OS processes, using portable shell
 * commands that any reasonable Windows / Linux / macOS host has.
 */
internal class JdkSubprocessRunnerTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Build an argv that prints [text] to stdout on the host. */
    private fun echo(text: String): List<String> = if (isWindows) {
        listOf("cmd", "/c", "echo $text")
    } else {
        listOf("sh", "-c", "echo $text")
    }

    /** An argv whose process exits with [code]. */
    private fun exitWith(code: Int): List<String> = if (isWindows) {
        listOf("cmd", "/c", "exit $code")
    } else {
        listOf("sh", "-c", "exit $code")
    }

    /** An argv that writes [text] to stderr. */
    private fun echoStderr(text: String): List<String> = if (isWindows) {
        listOf("cmd", "/c", "echo $text 1>&2")
    } else {
        listOf("sh", "-c", "echo $text 1>&2")
    }

    // ---------------------- run (one-shot) ----------------------

    @Test
    fun `run captures stdout and zero exit on a trivial echo`() {
        val result = JdkSubprocessRunner.run(echo("hello"))
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.contains("hello"),
            "stdout had: '${result.stdout}'")
    }

    @Test
    fun `run propagates a non-zero exit code`() {
        val result = JdkSubprocessRunner.run(exitWith(7))
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `run separates stderr when redirectErrorStream is false`() {
        val result = JdkSubprocessRunner.run(
            echoStderr("oops"),
            redirectErrorStream = false,
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("oops"),
            "stderr had: '${result.stderr}'")
        assertFalse(result.stdout.contains("oops"),
            "stdout shouldn't carry stderr bytes when redirectErrorStream=false")
    }

    @Test
    fun `run merges stderr into stdout when redirectErrorStream is true`() {
        val result = JdkSubprocessRunner.run(
            echoStderr("merged"),
            redirectErrorStream = true,
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("merged"),
            "stdout had: '${result.stdout}'")
    }

    // ---------------------- start (streaming) ----------------------

    @Test
    fun `start hands back a JdkStreamingProcess that echoes through stdout`() {
        val proc = JdkSubprocessRunner.start(echo("streaming"))
        try {
            val text = proc.stdout.bufferedReader().readText()
            // waitFor unblocks once the shell exits.
            val exit = proc.waitFor()
            assertEquals(0, exit)
            assertTrue(text.contains("streaming"), "stdout had: '$text'")
            assertFalse(proc.isAlive)
            assertTrue(proc.pid > 0)
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `start surfaces non-zero exit via exitValue after waitFor`() {
        val proc = JdkSubprocessRunner.start(exitWith(3))
        try {
            proc.waitFor()
            assertEquals(3, proc.exitValue())
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `bounded waitFor returns false when the process outlives the timeout`() {
        // `sleep 5` / `timeout /t 5` runs long enough that the bounded
        // waitFor must time out at 100ms.
        val cmd = if (isWindows) listOf("cmd", "/c", "ping -n 5 127.0.0.1 > nul")
            else listOf("sh", "-c", "sleep 5")
        val proc = JdkSubprocessRunner.start(cmd)
        try {
            val finished = proc.waitFor(Duration.ofMillis(100))
            assertFalse(finished, "long-running process must outlast the bounded wait")
            assertTrue(proc.isAlive)
        } finally {
            proc.destroyForcibly()
        }
    }
}
