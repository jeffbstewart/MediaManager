package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.ForBrowserProbe
import net.stewart.mediamanager.entity.LeaseStatus
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.TranscodeLease
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [TranscoderAgent.processTranscodeLease] — the
 * happy-path FFmpeg transcode loop, plus the failure / cancellation
 * branches. Drives the method directly with hand-crafted Title /
 * Transcode / TranscodeLease rows and a [FakeSubprocessRunner] scripted
 * to handle the three subprocess calls the loop makes:
 *
 *   1. `probeVideo` (one-shot ffmpeg `-i source.mkv`)
 *   2. The transcode itself (streaming start that yields progress lines)
 *   3. `probeForBrowser` (one-shot ffmpeg `-i out.mp4`) on success
 *
 * The agent uses real `java.io.File` for path arithmetic (mkdirs,
 * renameTo, tmp delete), so the test seeds a real temp directory as
 * the nasRoot and relies on the streaming-fake's build lambda to drop
 * the `.tmp` file production code will rename into the final MP4.
 */
internal class TranscoderAgentProcessLeaseTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:transcoderprocesslease;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @get:Rule val subprocs = SubprocessRule()

    private lateinit var nasRoot: File
    private lateinit var sourceFile: File
    private lateinit var agent: TranscoderAgent

    @Before
    fun reset() {
        ForBrowserProbe.deleteAll()
        TranscodeLease.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()

        // Real on-disk temp tree so File.mkdirs / renameTo behave as in
        // production. Layout:
        //   <nasRoot>/movies/Title.mkv          ← source
        //   <nasRoot>/ForBrowser/movies/...mp4  ← output (created by test)
        nasRoot = Files.createTempDirectory("transcode-lease-").toFile().apply {
            deleteOnExit()
        }
        val moviesDir = File(nasRoot, "movies").apply { mkdirs() }
        sourceFile = File(moviesDir, "Title.mkv").apply {
            writeBytes(ByteArray(0))
            deleteOnExit()
        }

        agent = TranscoderAgent()
        agent.running.set(true)
    }

    /**
     * Seeds a Title + Transcode + TranscodeLease triplet pointing at
     * [sourceFile] and returns the lease for the test to drive.
     */
    private fun seedLease(
        leaseType: LeaseType = LeaseType.TRANSCODE,
        relativePath: String = "movies/Title.mkv",
    ): TranscodeLease {
        val title = Title(name = "Title", media_type = MediaType.MOVIE.name,
            sort_name = "title").apply { save() }
        val tc = Transcode(title_id = title.id!!, file_path = sourceFile.absolutePath,
            file_size_bytes = 1_000_000L).apply { save() }
        return TranscodeLease(
            transcode_id = tc.id!!,
            buddy_name = "local",
            relative_path = relativePath,
            file_size_bytes = tc.file_size_bytes,
            claimed_at = LocalDateTime.now(),
            expires_at = LocalDateTime.now().plusMinutes(5),
            status = LeaseStatus.CLAIMED.name,
            lease_type = leaseType.name,
        ).apply { save() }
    }

    /** Probe stdout that satisfies VideoProbe's regex set. */
    private val probeStdoutBrowserSafe = """
        Input #0, matroska,webm, from 'Title.mkv':
          Duration: 00:30:00.00, start: 0.000000, bitrate: 5000 kb/s
          Stream #0:0(eng): Video: h264 (High), yuv420p(tv, bt709), 1920x1080 [SAR 1:1 DAR 16:9], 23.976 fps, 23.976 tbr
          Stream #0:1(eng): Audio: ac3, 48000 Hz, stereo, fltp, 192 kb/s
    """.trimIndent()

    private val forBrowserProbeStdout = """
        Input #0, mov,mp4, from 'Title.mp4':
          Duration: 00:30:00.00, start: 0.000000, bitrate: 5000 kb/s
          Stream #0:0: Video: h264 (High), yuv420p, 1920x1080 [SAR 1:1 DAR 16:9], 23.976 fps
          Stream #0:1: Audio: aac (LC), 48000 Hz, stereo, 192 kb/s
    """.trimIndent()

    /** FFmpeg progress lines that drive the time= regex inside the loop. */
    private val transcodeStreamingStdout = """
        frame=  100 fps= 50 q=20.0 size=  1024kB time=00:05:00.00 bitrate=...
        frame=  200 fps= 50 q=20.0 size=  2048kB time=00:15:00.00 bitrate=...
        frame=  300 fps= 50 q=20.0 size=  3072kB time=00:30:00.00 bitrate=...
    """.trimIndent() + "\n"

    // ---------------------- happy path ----------------------

    @Test
    fun `processTranscodeLease completes the FFmpeg run, renames tmp to mp4, and records the probe`() {
        val lease = seedLease()
        val transcodes = Transcode.findAll()

        // 1. probeVideo (`ffmpeg -i source`) — match by argv length 3 with -i.
        subprocs.fake.on(
            matcher = { argv ->
                argv.size == 3 && argv[1] == "-i" && argv[2] == sourceFile.absolutePath
            },
            stdout = probeStdoutBrowserSafe,
            exitCode = 0,
        )

        // 2. The streaming transcode call — matches by binary basename.
        //    The build lambda drops the .tmp file production code will
        //    rename into the final MP4. The FakeStreamingProcess is
        //    pre-baked with progress stdout and exit code 0.
        subprocs.fake.onStartBinary("ffmpeg") { argv ->
            // The output path is the last argv entry by convention. Drop
            // a `.tmp` file there so production's `tmpFile.renameTo`
            // succeeds.
            val outputPath = argv.last()
            File(outputPath).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(2_000_000))  // simulate the encoded MP4
            }
            FakeStreamingProcess(
                stdoutContent = transcodeStreamingStdout,
                initialExitCode = 0,
            )
        }

        // 3. probeForBrowser — match by `ffmpeg -i <output.mp4>`.
        val expectedOutputPath = TranscoderAgent.getForBrowserPath(
            nasRoot.absolutePath, sourceFile.absolutePath
        ).absolutePath
        subprocs.fake.on(
            matcher = { argv ->
                argv.size == 3 && argv[1] == "-i" && argv[2] == expectedOutputPath
            },
            stdout = forBrowserProbeStdout,
            exitCode = 0,
        )

        agent.processTranscodeLease(
            lease = lease,
            nasRoot = nasRoot.absolutePath,
            ffmpegPath = "ffmpeg",
            transcodes = transcodes,
            totalCompletedIn = 0,
            bundleLeaseIds = listOf(lease.id!!),
        )

        // Lease moved to COMPLETED.
        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.COMPLETED.name, refreshed.status)
        assertNotNull(refreshed.completed_at)

        // Output MP4 exists at the ForBrowser path; tmp is gone.
        assertTrue(File(expectedOutputPath).exists(),
            "ForBrowser output mp4 should exist at $expectedOutputPath")
        val tmpPath = File(expectedOutputPath).parentFile
            .resolve(File(expectedOutputPath).nameWithoutExtension + ".tmp")
        assertFalse(tmpPath.exists(), "tmp file should have been renamed away")

        // ForBrowserProbe row was written by recordProbe.
        val probes = ForBrowserProbe.findAll().filter { it.transcode_id == refreshed.transcode_id }
        assertEquals(1, probes.size)
        assertEquals(2, probes.first().stream_count)
    }

    // ---------------------- ffmpeg exit code != 0 ----------------------

    @Test
    fun `processTranscodeLease records failure when ffmpeg exits non-zero`() {
        val lease = seedLease()
        val transcodes = Transcode.findAll()

        subprocs.fake.on(
            matcher = { argv -> argv.size == 3 && argv[1] == "-i" },
            stdout = probeStdoutBrowserSafe, exitCode = 0,
        )
        subprocs.fake.onStartBinary("ffmpeg") {
            FakeStreamingProcess(
                stdoutContent = "Error initializing output stream\n",
                initialExitCode = 1,  // non-zero exit
            )
        }

        agent.processTranscodeLease(
            lease = lease,
            nasRoot = nasRoot.absolutePath,
            ffmpegPath = "ffmpeg",
            transcodes = transcodes,
            totalCompletedIn = 0,
            bundleLeaseIds = listOf(lease.id!!),
        )

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertTrue(refreshed.error_message?.contains("FFmpeg exit code 1") == true,
            "error message should mention ffmpeg exit code, got: ${refreshed.error_message}")

        // No ForBrowser MP4 left behind.
        val expectedOutputPath = TranscoderAgent.getForBrowserPath(
            nasRoot.absolutePath, sourceFile.absolutePath
        ).absolutePath
        assertFalse(File(expectedOutputPath).exists())
    }

    // ---------------------- early shutdown via running.set(false) ----------------------

    @Test
    fun `processTranscodeLease bails out early when running is flipped to false during the loop`() {
        val lease = seedLease()
        val transcodes = Transcode.findAll()

        subprocs.fake.on(
            matcher = { argv -> argv.size == 3 && argv[1] == "-i" },
            stdout = probeStdoutBrowserSafe, exitCode = 0,
        )
        // Streaming fake: emit one progress line; the test thread will
        // flip running=false before the line is processed by hooking the
        // build lambda to do it synchronously. The forEachLine guard then
        // calls destroyForcibly and returns from the lambda.
        subprocs.fake.onStartBinary("ffmpeg") { argv ->
            val outputPath = argv.last()
            File(outputPath).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(0))
            }
            // Production reads stdout via forEachLine; the first call to
            // running.get() inside the loop must see false. Flip it now,
            // before the StreamingProcess is exposed to the consumer.
            agent.running.set(false)
            FakeStreamingProcess(
                stdoutContent = "frame=1 fps=30 time=00:00:01.00 bitrate=...\n",
                initialExitCode = 0,
            )
        }

        agent.processTranscodeLease(
            lease = lease,
            nasRoot = nasRoot.absolutePath,
            ffmpegPath = "ffmpeg",
            transcodes = transcodes,
            totalCompletedIn = 0,
            bundleLeaseIds = listOf(lease.id!!),
        )

        // The loop saw running=false on the first line, called
        // destroyForcibly, then waited and returned without renaming
        // the tmp into mp4. Lease stays in CLAIMED — no completion or
        // failure was reported on the cancelled run.
        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.CLAIMED.name, refreshed.status,
            "cancelled mid-run leases stay in CLAIMED; the next claim cycle re-leases them")
    }

    // ---------------------- exception during transcode ----------------------

    @Test
    fun `processTranscodeLease records failure when probeVideo throws`() {
        val lease = seedLease()
        val transcodes = Transcode.findAll()

        // No matchers registered — any subprocess call will fail loudly.
        // The FakeSubprocessRunner errors on unmatched calls, which the
        // outer try/catch in processTranscodeLease converts into a
        // reportFailure path.

        agent.processTranscodeLease(
            lease = lease,
            nasRoot = nasRoot.absolutePath,
            ffmpegPath = "ffmpeg",
            transcodes = transcodes,
            totalCompletedIn = 0,
            bundleLeaseIds = listOf(lease.id!!),
        )

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertNotNull(refreshed.error_message)
    }

    // The "Transcode row gone" early-return branch is unreachable in
    // tests against the real schema — the FK fk_lease_transcode prevents
    // a TranscodeLease from ever pointing at a non-existent transcode_id.
    // It would only fire under explicit FK violations that the framework
    // wouldn't allow us to construct. Skipped intentionally.
}
