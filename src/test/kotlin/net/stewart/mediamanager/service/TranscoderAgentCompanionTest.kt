package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Companion-object tests for [TranscoderAgent] — the path arithmetic,
 * existence-check helpers, config reads, and FFmpeg-info probes. Uses
 * the previously-built [SubprocessRule] for the probes and [JimfsRule]
 * for the existence checks.
 *
 * The instance-level transcode loop (`processTranscodeLease`,
 * `processNext`) needs much heavier scaffolding (DB lease + transcode +
 * title rows, broadcasts, MetricsRegistry side effects) and stays out
 * of scope for this slice.
 */
internal class TranscoderAgentCompanionTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:transcodercompanion;DB_CLOSE_DELAY=-1"
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

    @get:Rule val fsRule = JimfsRule()
    @get:Rule val subprocs = SubprocessRule()

    @Before
    fun reset() {
        AppConfig.deleteAll()
    }

    // ---------------------- needsTranscoding ----------------------

    @Test
    fun `needsTranscoding flags mkv and avi sources but leaves mp4 alone`() {
        assertTrue(TranscoderAgent.needsTranscoding("/nas/movies/foo.mkv"))
        assertTrue(TranscoderAgent.needsTranscoding("/nas/movies/foo.avi"))
        assertTrue(TranscoderAgent.needsTranscoding("/nas/movies/foo.MKV"),
            "extension match is case-insensitive")
        assertFalse(TranscoderAgent.needsTranscoding("/nas/movies/foo.mp4"))
        assertFalse(TranscoderAgent.needsTranscoding("/nas/movies/foo.m4v"))
        assertFalse(TranscoderAgent.needsTranscoding("/nas/movies/oddball.wmv"))
    }

    // ---------------------- isTranscoded / isMobileTranscoded ----------------------

    @Test
    fun `isTranscoded checks the mirrored ForBrowser path against Filesystems-current`() {
        assertFalse(TranscoderAgent.isTranscoded("/nas", "/nas/movies/foo.mkv"))
        fsRule.seed("/nas/ForBrowser/movies/foo.mp4")
        assertTrue(TranscoderAgent.isTranscoded("/nas", "/nas/movies/foo.mkv"))
    }

    @Test
    fun `isMobileTranscoded checks the mirrored ForMobile path against Filesystems-current`() {
        assertFalse(TranscoderAgent.isMobileTranscoded("/nas", "/nas/movies/foo.mkv"))
        fsRule.seed("/nas/ForMobile/movies/foo.mp4")
        assertTrue(TranscoderAgent.isMobileTranscoded("/nas", "/nas/movies/foo.mkv"))
    }

    // ---------------------- getForMobilePath ----------------------

    @Test
    fun `getForMobilePath mirrors the source under nasRoot ForMobile with mp4 extension`() {
        val out = TranscoderAgent.getForMobilePath("/nas", "/nas/movies/Title.mkv")
        // Path arithmetic via java.io.File — host-native separators after
        // normalization. We just assert the meaningful pieces.
        assertTrue(out.path.contains("ForMobile"))
        assertTrue(out.path.endsWith("Title.mp4"))
    }

    // ---------------------- findAuxFile ----------------------

    @Test
    fun `findAuxFile prefers the canonical sibling in the source directory`() {
        fsRule.seed("/nas/movies/Foo.en.srt")
        val found = TranscoderAgent.findAuxFile(
            nasRoot = "/nas",
            sourceFilePath = "/nas/movies/Foo.mkv",
            suffix = ".en.srt",
        )
        assertEquals("/nas/movies/Foo.en.srt", found?.path?.replace('\\', '/'))
    }

    @Test
    fun `findAuxFile falls back to the ForBrowser sibling when source-dir is empty`() {
        fsRule.seed("/nas/ForBrowser/movies/Foo.en.srt")
        val found = TranscoderAgent.findAuxFile(
            nasRoot = "/nas",
            sourceFilePath = "/nas/movies/Foo.mkv",
            suffix = ".en.srt",
        )
        assertTrue(found!!.path.replace('\\', '/').contains("/ForBrowser/"))
    }

    @Test
    fun `findAuxFile returns null when neither location holds the sibling`() {
        val found = TranscoderAgent.findAuxFile("/nas", "/nas/movies/Foo.mkv", ".en.srt")
        assertNull(found)
    }

    @Test
    fun `findAuxFile skips the ForBrowser fallback for non-transcoded sources`() {
        // mp4 doesn't need transcoding → no ForBrowser fallback.
        fsRule.seed("/nas/ForBrowser/movies/Foo.en.srt")
        val found = TranscoderAgent.findAuxFile("/nas", "/nas/movies/Foo.mp4", ".en.srt")
        assertNull(found,
            "ForBrowser fallback only applies to mkv/avi sources")
    }

    @Test
    fun `findAuxFile tolerates a null nasRoot by skipping the ForBrowser fallback`() {
        fsRule.seed("/nas/movies/Foo.en.srt")
        val found = TranscoderAgent.findAuxFile(
            nasRoot = null,
            sourceFilePath = "/nas/movies/Foo.mkv",
            suffix = ".en.srt",
        )
        assertEquals("/nas/movies/Foo.en.srt", found?.path?.replace('\\', '/'))
    }

    // ---------------------- getNasRoot / getFfmpegPath / getDefaultFfmpegPath ----------------------

    @Test
    fun `getNasRoot reads the nas_root_path AppConfig key`() {
        assertNull(TranscoderAgent.getNasRoot())
        AppConfig(config_key = "nas_root_path", config_val = "/srv/media").save()
        assertEquals("/srv/media", TranscoderAgent.getNasRoot())
    }

    @Test
    fun `getFfmpegPath returns the configured value when the file exists on disk`() {
        // Use a real on-host file the production check can see (this
        // method's existence check still uses File, not Filesystems.current).
        val tmp = java.nio.file.Files.createTempFile("ffmpeg-fake-", ".bin").toFile()
        tmp.deleteOnExit()
        AppConfig(config_key = "ffmpeg_path", config_val = tmp.absolutePath).save()
        assertEquals(tmp.absolutePath, TranscoderAgent.getFfmpegPath())
    }

    @Test
    fun `getFfmpegPath falls back to the platform default when configured value does not exist`() {
        AppConfig(config_key = "ffmpeg_path",
            config_val = "/no/such/ffmpeg-binary").save()
        // Fallback is the OS-specific DEFAULT_FFMPEG_PATH constant.
        val result = TranscoderAgent.getFfmpegPath()
        assertEquals(TranscoderAgent.getDefaultFfmpegPath(), result)
    }

    @Test
    fun `getDefaultFfmpegPath is OS-specific`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val expected = if (isWindows) "C:\\ffmpeg\\bin\\ffmpeg.exe" else "/usr/bin/ffmpeg"
        assertEquals(expected, TranscoderAgent.getDefaultFfmpegPath())
    }

    // ---------------------- probeAudioChannels ----------------------

    @Test
    fun `probeAudioChannels parses 5 dot 1 layout from an ffmpeg dump`() {
        subprocs.fake.onBinary("ffmpeg",
            stdout = """
                Input #0, matroska,webm, from 'foo.mkv':
                  Stream #0:0(eng): Video: h264 (High), yuv420p(tv, bt709)
                  Stream #0:1(eng): Audio: ac3, 48000 Hz, 5.1, fltp, 448 kb/s
            """.trimIndent())

        val channels = TranscoderAgent.probeAudioChannels("ffmpeg", File("foo.mkv"))
        assertEquals(6, channels)
    }

    private fun assertLayoutMapsTo(layout: String, expected: Int) {
        subprocs.fake.onBinary("ffmpeg",
            stdout = "Stream #0:1(eng): Audio: aac (LC), 48000 Hz, $layout, fltp, 192 kb/s\n")
        assertEquals(expected,
            TranscoderAgent.probeAudioChannels("ffmpeg", File("test.mkv")),
            "layout '$layout' should map to $expected")
    }

    @Test fun `probeAudioChannels mono maps to 1`() = assertLayoutMapsTo("mono", 1)
    @Test fun `probeAudioChannels stereo maps to 2`() = assertLayoutMapsTo("stereo", 2)
    @Test fun `probeAudioChannels 5_1 maps to 6`() = assertLayoutMapsTo("5.1", 6)
    @Test fun `probeAudioChannels 5_1(side) maps to 6`() = assertLayoutMapsTo("5.1(side)", 6)
    @Test fun `probeAudioChannels 7_1 maps to 8`() = assertLayoutMapsTo("7.1", 8)
    @Test fun `probeAudioChannels quad maps to 4`() = assertLayoutMapsTo("quad", 4)

    @Test
    fun `probeAudioChannels returns null when the regex finds no audio stream`() {
        subprocs.fake.onBinary("ffmpeg",
            stdout = "Input #0, matroska,webm, from 'foo.mkv':\nNo audio stream\n")
        assertNull(TranscoderAgent.probeAudioChannels("ffmpeg", File("foo.mkv")))
    }

    @Test
    fun `probeAudioChannels returns null for an unknown layout label`() {
        subprocs.fake.onBinary("ffmpeg",
            stdout = "Stream #0:1: Audio: aac, 48000 Hz, 9.1.4, fltp, 192 kb/s\n")
        assertNull(TranscoderAgent.probeAudioChannels("ffmpeg", File("foo.mkv")))
    }

    // ---------------------- probeStreamCount ----------------------

    @Test
    fun `probeStreamCount counts every Stream marker in the merged output`() {
        subprocs.fake.onBinary("ffmpeg",
            stdout = """
                Stream #0:0: Video: h264
                Stream #0:1: Audio: ac3
                Stream #0:2: Subtitle: subrip
            """.trimIndent())
        assertEquals(3, TranscoderAgent.probeStreamCount("ffmpeg", File("foo.mkv")))
    }

    @Test
    fun `probeStreamCount returns 0 when ffmpeg dumps nothing recognizable`() {
        subprocs.fake.onBinary("ffmpeg", stdout = "Invalid data found\n")
        assertEquals(0, TranscoderAgent.probeStreamCount("ffmpeg", File("foo.mkv")))
    }
}
