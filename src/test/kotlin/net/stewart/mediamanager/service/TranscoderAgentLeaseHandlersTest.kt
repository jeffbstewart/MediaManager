package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Chapter
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
 * Coverage for the remaining [TranscoderAgent] lease handlers:
 * `processChaptersLease`, `processThumbnails`, `processSubtitles`, and
 * the `handleRetranscodeRequests` helper. Same scaffolding as
 * [TranscoderAgentProcessLeaseTest] — real H2 + Flyway, real on-disk
 * temp tree for the NAS, and the [SubprocessRule] for ffmpeg/ffprobe
 * fakes.
 */
internal class TranscoderAgentLeaseHandlersTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:transcoderhandlers;DB_CLOSE_DELAY=-1"
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
        Chapter.deleteAll()
        TranscodeLease.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()

        nasRoot = Files.createTempDirectory("transcode-handlers-").toFile().apply {
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
     * Seeds Title + Transcode + TranscodeLease pointing at the per-test
     * source file and returns the lease.
     */
    private fun seedLease(
        leaseType: LeaseType,
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

    // ---------------------- processChaptersLease ----------------------

    /** ffprobe JSON for two chapters. */
    private val twoChapterProbe = """
        {
          "chapters": [
            {
              "id": 0,
              "start_time": "0.000000",
              "end_time": "300.000000",
              "tags": { "title": "Opening" }
            },
            {
              "id": 1,
              "start_time": "300.000000",
              "end_time": "600.000000",
              "tags": { "title": "Middle" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `processChaptersLease parses ffprobe output and stores Chapter rows`() {
        val lease = seedLease(LeaseType.CHAPTERS)
        // ffprobe binary is the second positional arg the production code
        // forks; we match by the first argv ending in "ffprobe".
        subprocs.fake.onBinary("ffprobe",
            stdout = twoChapterProbe, exitCode = 0)

        agent.processChaptersLease(lease, nasRoot.absolutePath, "ffmpeg")

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.COMPLETED.name, refreshed.status)
        // Chapter rows persisted.
        val chapters = Chapter.findAll()
            .filter { it.transcode_id == lease.transcode_id }
            .sortedBy { it.chapter_number }
        assertEquals(2, chapters.size)
        assertEquals(1, chapters[0].chapter_number)
        assertEquals("Opening", chapters[0].title)
        assertEquals(2, chapters[1].chapter_number)
        assertEquals("Middle", chapters[1].title)
    }

    @Test
    fun `processChaptersLease handles ffprobe returning no chapters gracefully`() {
        val lease = seedLease(LeaseType.CHAPTERS)
        // Empty chapters JSON — production parses as empty list and
        // completes without storing rows.
        subprocs.fake.onBinary("ffprobe",
            stdout = """{"chapters":[]}""", exitCode = 0)

        agent.processChaptersLease(lease, nasRoot.absolutePath, "ffmpeg")

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.COMPLETED.name, refreshed.status)
        assertEquals(0, Chapter.findAll()
            .count { it.transcode_id == lease.transcode_id })
    }

    @Test
    fun `processChaptersLease replaces prior chapters on re-run (idempotent)`() {
        val lease = seedLease(LeaseType.CHAPTERS)
        // Pre-existing stale chapter row.
        Chapter(transcode_id = lease.transcode_id, chapter_number = 99,
            start_seconds = 0.0, end_seconds = 1.0,
            title = "Stale").create()
        subprocs.fake.onBinary("ffprobe",
            stdout = twoChapterProbe, exitCode = 0)

        agent.processChaptersLease(lease, nasRoot.absolutePath, "ffmpeg")

        val chapters = Chapter.findAll()
            .filter { it.transcode_id == lease.transcode_id }
        assertEquals(2, chapters.size,
            "old chapter wiped; two new chapters from the fresh probe")
        assertTrue(chapters.none { it.title == "Stale" })
    }

    @Test
    fun `processChaptersLease reports failure when source file is missing on disk`() {
        val lease = seedLease(LeaseType.CHAPTERS)
        // Delete the source file so File(path).exists() returns false.
        sourceFile.delete()

        agent.processChaptersLease(lease, nasRoot.absolutePath, "ffmpeg")

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertTrue(refreshed.error_message?.contains("Source file not found") == true)
    }

    // ---------------------- processThumbnails ----------------------

    @Test
    fun `processThumbnails reports failure when ffmpeg duration probe yields nothing`() {
        val lease = seedLease(LeaseType.THUMBNAILS)
        // ThumbnailSpriteGenerator.probeDurationSecs runs `ffmpeg -i source`
        // first and parses Duration: from the output. With no Duration
        // line, generate() returns false → reportFailure.
        subprocs.fake.onBinary("ffmpeg",
            stdout = "Invalid data", exitCode = 0)

        agent.processThumbnails(lease, nasRoot.absolutePath, "ffmpeg")

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertTrue(refreshed.error_message?.contains("FFmpeg thumbnail generation failed") == true,
            "got: ${refreshed.error_message}")
    }

    @Test
    fun `processThumbnails reports failure when neither source nor ForBrowser exists`() {
        val lease = seedLease(LeaseType.THUMBNAILS)
        sourceFile.delete()
        // No ForBrowser file either — production hits the second
        // existence check and falls through to reportFailure.
        agent.processThumbnails(lease, nasRoot.absolutePath, "ffmpeg")

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertNotNull(refreshed.error_message)
    }

    // ---------------------- processSubtitles ----------------------

    @Test
    fun `processSubtitles always reports failure for the local agent`() {
        val lease = seedLease(LeaseType.SUBTITLES)
        // Local agent has no Whisper — production short-circuits with
        // a clear failure message.
        agent.processSubtitles(lease, nasRoot.absolutePath)

        val refreshed = TranscodeLease.findById(lease.id!!)!!
        assertEquals(LeaseStatus.FAILED.name, refreshed.status)
        assertTrue(refreshed.error_message?.contains("Whisper") == true,
            "got: ${refreshed.error_message}")
    }

    // ---------------------- handleRetranscodeRequests ----------------------

    @Test
    fun `handleRetranscodeRequests is a no-op when there are no pending re-transcodes`() {
        val title = Title(name = "Untouched", media_type = MediaType.MOVIE.name,
            sort_name = "untouched").apply { save() }
        Transcode(title_id = title.id!!,
            file_path = sourceFile.absolutePath,
            retranscode_requested = false).save()

        // No assertions on side effects — just confirm it doesn't throw
        // and leaves the row untouched.
        agent.handleRetranscodeRequests(nasRoot.absolutePath)

        val tcs = Transcode.findAll()
        assertEquals(false, tcs.single().retranscode_requested)
    }

    @Test
    fun `handleRetranscodeRequests skips rows whose ForBrowser copy doesn't exist`() {
        val title = Title(name = "Stale", media_type = MediaType.MOVIE.name,
            sort_name = "stale").apply { save() }
        // retranscode_requested = true but no ForBrowser file yet — the
        // helper checks fb.exists() and skips.
        Transcode(title_id = title.id!!,
            file_path = sourceFile.absolutePath,
            file_size_bytes = 100L,
            retranscode_requested = true).save()

        agent.handleRetranscodeRequests(nasRoot.absolutePath)

        // Row unchanged — request still pending.
        val refreshed = Transcode.findAll().single()
        assertTrue(refreshed.retranscode_requested,
            "no ForBrowser file → request stays pending for next pass")
    }

    @Test
    fun `handleRetranscodeRequests deletes ForBrowser when source has been replaced`() {
        val title = Title(name = "Replaced", media_type = MediaType.MOVIE.name,
            sort_name = "replaced").apply { save() }
        // Old size on the row; the file on disk is much larger.
        sourceFile.writeBytes(ByteArray(500))  // grow source to 500 bytes
        val tc = Transcode(title_id = title.id!!,
            file_path = sourceFile.absolutePath,
            file_size_bytes = 100L,  // recorded size doesn't match disk
            retranscode_requested = true).apply { save() }
        // Drop a ForBrowser file at the mirrored path so the helper
        // sees it and decides to delete.
        val fb = TranscoderAgent.getForBrowserPath(
            nasRoot.absolutePath, sourceFile.absolutePath)
        fb.parentFile.mkdirs()
        fb.writeBytes(ByteArray(2000))

        agent.handleRetranscodeRequests(nasRoot.absolutePath)

        // ForBrowser deleted, retranscode flag cleared, file_size synced.
        assertFalse(fb.exists())
        val refreshed = Transcode.findById(tc.id!!)!!
        assertFalse(refreshed.retranscode_requested)
        assertEquals(500L, refreshed.file_size_bytes)
    }

    @Test
    fun `handleRetranscodeRequests does not delete ForBrowser when source matches recorded size`() {
        val title = Title(name = "Match", media_type = MediaType.MOVIE.name,
            sort_name = "match").apply { save() }
        // Source size matches recorded size, no modified-time delta.
        sourceFile.writeBytes(ByteArray(100))
        Transcode(title_id = title.id!!,
            file_path = sourceFile.absolutePath,
            file_size_bytes = 100L,  // matches disk
            retranscode_requested = true).save()

        val fb = TranscoderAgent.getForBrowserPath(
            nasRoot.absolutePath, sourceFile.absolutePath)
        fb.parentFile.mkdirs()
        fb.writeBytes(ByteArray(2000))

        agent.handleRetranscodeRequests(nasRoot.absolutePath)

        // ForBrowser preserved — the source file is unchanged so we're
        // still waiting for the user to actually replace the MKV.
        assertTrue(fb.exists())
        val refreshed = Transcode.findAll().single()
        assertTrue(refreshed.retranscode_requested,
            "request stays pending until source actually changes")
    }
}
