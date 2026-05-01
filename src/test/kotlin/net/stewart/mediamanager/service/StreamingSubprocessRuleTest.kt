package net.stewart.mediamanager.service

import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Demonstrates the streaming integration path: register a fake via
 * [SubprocessRule.fake.onStart], call [Subprocesses.current.start] the
 * way production code does, and consume stdout line-by-line + check
 * the exit code — exactly the pattern TranscoderAgent / Go2rtcAgent
 * / LiveTvStreamManager follow.
 */
internal class StreamingSubprocessRuleTest {

    @get:Rule val subprocs = SubprocessRule()

    @Test
    fun `onStart hands back the scripted process and the full pattern works end-to-end`() {
        // Simulates ffmpeg's progress lines (merged stdout with
        // redirectErrorStream=true, as TranscoderAgent uses).
        subprocs.fake.onStartBinary("ffmpeg") {
            FakeStreamingProcess(
                stdoutContent = """
                    frame=   10 fps=10 time=00:00:01.00 bitrate=...
                    frame=  100 fps=50 time=00:00:10.00 bitrate=...
                    frame=  500 fps=50 time=00:00:50.00 bitrate=...
                """.trimIndent() + "\n",
                initialExitCode = 0,
            )
        }

        // Production-flow shape: start, drain stdout, waitFor, exitValue.
        val command = listOf("ffmpeg", "-i", "in.mkv", "-c:v", "libx264", "out.mp4")
        val proc = Subprocesses.current.start(command, redirectErrorStream = true)

        val timeRegex = Regex("""time=(\d+):(\d+):(\d+)\.(\d+)""")
        val capturedSeconds = mutableListOf<Double>()
        proc.stdout.bufferedReader().forEachLine { line ->
            timeRegex.find(line)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mm = m.groupValues[2].toInt()
                val s = m.groupValues[3].toInt()
                val frac = "0.${m.groupValues[4]}".toDouble()
                capturedSeconds += h * 3600.0 + mm * 60.0 + s + frac
            }
        }
        val exit = proc.waitFor()

        assertEquals(0, exit)
        assertEquals(0, proc.exitValue())
        assertFalse(proc.isAlive)
        assertEquals(listOf(1.0, 10.0, 50.0), capturedSeconds,
            "the time-progress regex pulls one Double per scripted line")

        // The fake recorded the exact command production code asked for.
        val invoked = subprocs.fake.invocations.single()
        assertEquals(command, invoked)
    }

    @Test
    fun `streamingInvocations exposes the fake process for post-call assertions`() {
        subprocs.fake.onStartBinary("go2rtc") {
            FakeStreamingProcess(stdoutContent = "INFO go2rtc started on port 1984\n")
        }

        val proc = Subprocesses.current.start(listOf("go2rtc", "-c", "/tmp/cfg.yaml"),
            redirectErrorStream = true)

        // Test thread can later flip the process to "exited" — mirrors
        // a daemon shutdown path. Production code that polls isAlive
        // sees the transition immediately.
        assertTrue(proc.isAlive)
        val fake = subprocs.fake.streamingInvocations.single()
        fake.simulateExit(0)
        assertFalse(proc.isAlive)
        assertEquals(0, proc.waitFor())
    }

    @Test
    fun `destroyForcibly is recorded so production shutdown paths can be asserted`() {
        subprocs.fake.onStartBinary("ffmpeg") { FakeStreamingProcess() }

        val proc = Subprocesses.current.start(listOf("ffmpeg", "-i", "live.ts"))
        // Production shutdown calls destroyForcibly to kill ffmpeg.
        proc.destroyForcibly()

        val fake = subprocs.fake.streamingInvocations.single()
        assertTrue(fake.destroyForciblyInvoked)
        assertFalse(proc.isAlive)
    }

    @Test
    fun `unmatched start fails loudly so silent-pass tests are impossible`() {
        // No rule registered → start() throws.
        val ex = runCatching {
            Subprocesses.current.start(listOf("ffmpeg", "-i", "missing.mkv"))
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException,
            "missing rule should raise IllegalStateException, got $ex")
        assertTrue(ex.message!!.contains("ffmpeg"))
    }
}
