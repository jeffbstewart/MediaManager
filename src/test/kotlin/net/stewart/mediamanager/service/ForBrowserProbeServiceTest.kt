package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import net.stewart.transcode.ForBrowserProbeResult
import net.stewart.transcode.StreamInfo
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class ForBrowserProbeServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:probetest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private lateinit var testTitle: Title
    private lateinit var testTranscode: Transcode

    @Before
    fun setup() {
        ForBrowserProbeStream.deleteAll()
        ForBrowserProbe.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()

        testTitle = Title(name = "Test Movie", sort_name = "test movie")
        testTitle.save()

        testTranscode = Transcode(
            title_id = testTitle.id!!,
            file_path = "/nas/BLURAY/Test Movie.mkv",
            status = "LINKED"
        )
        testTranscode.save()
    }

    @After
    fun teardown() {
        ForBrowserProbeStream.deleteAll()
        ForBrowserProbe.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
    }

    private fun makeProbeResult(
        durationSecs: Double? = 120.5,
        videoCodec: String = "h264",
        audioCodec: String = "aac",
        width: Int = 1920,
        height: Int = 1080
    ): ForBrowserProbeResult {
        return ForBrowserProbeResult(
            durationSecs = durationSecs,
            streams = listOf(
                StreamInfo(0, "video", videoCodec, width, height, 1, 1, 23.976,
                    rawLine = "Stream #0:0: Video: h264 (High), yuv420p, ${width}x${height}"),
                StreamInfo(1, "audio", audioCodec, channels = 2, channelLayout = "stereo",
                    sampleRate = 48000, bitrateKbps = 192,
                    rawLine = "Stream #0:1: Audio: aac (LC), 48000 Hz, stereo, fltp, 192 kb/s")
            ),
            rawOutput = "ffmpeg version 6.1..."
        )
    }

    @Test
    fun `recordProbe creates probe and stream rows`() {
        val result = makeProbeResult()

        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = result,
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        val probes = ForBrowserProbe.findAll()
        assertEquals(1, probes.size)

        val probe = probes[0]
        assertEquals(testTranscode.id, probe.transcode_id)
        assertEquals("BLURAY/Test Movie.mp4", probe.relative_path)
        assertEquals(120.5, probe.duration_secs)
        assertEquals(2, probe.stream_count)
        assertEquals(5_000_000L, probe.file_size_bytes)
        assertEquals("libx264", probe.encoder)
        assertNotNull(probe.probed_at)

        val streams = ForBrowserProbeStream.findAll()
        assertEquals(2, streams.size)

        val video = streams.first { it.stream_type == "video" }
        assertEquals("h264", video.codec)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(0, video.stream_index)

        val audio = streams.first { it.stream_type == "audio" }
        assertEquals("aac", audio.codec)
        assertEquals(2, audio.channels)
        assertEquals(48000, audio.sample_rate)
        assertEquals(192, audio.bitrate_kbps)
    }

    @Test
    fun `recordProbe upserts on same transcode_id`() {
        val result1 = makeProbeResult(videoCodec = "mpeg2video", width = 720, height = 480)
        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = result1,
            encoder = "libx264",
            fileSize = 3_000_000L
        )

        assertEquals(1, ForBrowserProbe.findAll().size)
        assertEquals(2, ForBrowserProbeStream.findAll().size)

        // Record again with different data — should replace
        val result2 = makeProbeResult(videoCodec = "h264", width = 1920, height = 1080)
        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = result2,
            encoder = "h264_nvenc",
            fileSize = 5_000_000L
        )

        val probes = ForBrowserProbe.findAll()
        assertEquals(1, probes.size, "Upsert should replace, not duplicate")
        assertEquals("h264_nvenc", probes[0].encoder)
        assertEquals(5_000_000L, probes[0].file_size_bytes)

        val streams = ForBrowserProbeStream.findAll()
        assertEquals(2, streams.size, "Old streams should be cascade-deleted")
        val video = streams.first { it.stream_type == "video" }
        assertEquals("h264", video.codec)
        assertEquals(1920, video.width)
    }

    @Test
    fun `deleteForTranscode removes probe and streams`() {
        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = makeProbeResult(),
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        assertEquals(1, ForBrowserProbe.findAll().size)
        assertEquals(2, ForBrowserProbeStream.findAll().size)

        ForBrowserProbeService.deleteForTranscode(testTranscode.id!!)

        assertEquals(0, ForBrowserProbe.findAll().size)
        assertEquals(0, ForBrowserProbeStream.findAll().size)
    }

    @Test
    fun `hasProbe returns true when probe exists`() {
        assertFalse(ForBrowserProbeService.hasProbe(testTranscode.id!!))

        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = makeProbeResult(),
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        assertTrue(ForBrowserProbeService.hasProbe(testTranscode.id!!))
    }

    @Test
    fun `getAllProbesWithStreams returns probes with their streams`() {
        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = makeProbeResult(),
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        val probesWithStreams = ForBrowserProbeService.getAllProbesWithStreams()
        assertEquals(1, probesWithStreams.size)
        assertEquals(2, probesWithStreams[0].streams.size)
        assertEquals("BLURAY/Test Movie.mp4", probesWithStreams[0].probe.relative_path)
    }

    @Test
    fun `getProfileSummary groups by encoding profile`() {
        // Create two transcodes with same profile
        val tc2 = Transcode(
            title_id = testTitle.id!!,
            file_path = "/nas/BLURAY/Other Movie.mkv",
            status = "LINKED"
        )
        tc2.save()

        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = makeProbeResult(),
            encoder = "libx264",
            fileSize = 5_000_000L
        )
        ForBrowserProbeService.recordProbe(
            transcodeId = tc2.id!!,
            relativePath = "BLURAY/Other Movie.mp4",
            probeResult = makeProbeResult(),
            encoder = "libx264",
            fileSize = 4_000_000L
        )

        val profiles = ForBrowserProbeService.getProfileSummary()
        assertEquals(1, profiles.size, "Same codec/resolution should group together")
        assertEquals(2, profiles[0].count)
        assertEquals(2, profiles[0].files.size)
    }

    @Test
    fun `recordProbe handles probe with extra streams`() {
        val result = ForBrowserProbeResult(
            durationSecs = 90.0,
            streams = listOf(
                StreamInfo(0, "video", "h264", 1920, 1080, rawLine = "video line"),
                StreamInfo(1, "audio", "aac", channels = 2, rawLine = "audio line"),
                StreamInfo(2, "subtitle", "subrip", rawLine = "subtitle line"),
                StreamInfo(3, "data", "bin_data", rawLine = "data line")
            ),
            rawOutput = "full output"
        )

        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/Test Movie.mp4",
            probeResult = result,
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        val probe = ForBrowserProbe.findAll().first()
        assertEquals(4, probe.stream_count)

        val streams = ForBrowserProbeStream.findAll().sortedBy { it.stream_index }
        assertEquals(4, streams.size)
        assertEquals("video", streams[0].stream_type)
        assertEquals("audio", streams[1].stream_type)
        assertEquals("subtitle", streams[2].stream_type)
        assertEquals("data", streams[3].stream_type)
    }
}
