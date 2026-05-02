package net.stewart.mediamanager.service

import net.stewart.transcode.JdkSubprocessRunner
import net.stewart.transcode.Subprocesses
import org.junit.rules.ExternalResource

/**
 * JUnit 4 `@Rule` that swaps a [FakeSubprocessRunner] into
 * [Subprocesses.current] for the duration of a test, then restores the
 * production [JdkSubprocessRunner] in teardown.
 *
 * ```
 * @get:Rule val subprocs = SubprocessRule()
 *
 * @Test fun `ffprobe call returns scripted JSON`() {
 *     subprocs.fake.onBinary("ffprobe", stdout = """{"format": ...}""")
 *     // production code that calls Subprocesses.current.run(...)
 *     // sees the scripted result.
 * }
 * ```
 *
 * Pair with [JimfsRule] when the production code under test also reads
 * or writes through [Filesystems.current] — order is significant only
 * in the sense that both rules must be `@Rule`s on the same test class;
 * JUnit 4 applies them around every test method.
 */
internal class SubprocessRule : ExternalResource() {

    val fake: FakeSubprocessRunner = FakeSubprocessRunner()

    override fun before() {
        Subprocesses.current = fake
    }

    override fun after() {
        Subprocesses.current = JdkSubprocessRunner
    }
}
