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
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ReclassifyServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:reclassifytest;DB_CLOSE_DELAY=-1"
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

    private lateinit var nasRoot: File
    private lateinit var testTitle: Title
    private lateinit var testTranscode: Transcode

    @Before
    fun setup() {
        TranscodeLease.deleteAll()
        ForBrowserProbeStream.deleteAll()
        ForBrowserProbe.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()

        nasRoot = createTempDirectory("reclassify-test-nas").toFile()
        File(nasRoot, "BLURAY").mkdirs()
        File(nasRoot, "DVD").mkdirs()
        File(nasRoot, "UHD").mkdirs()
        File(nasRoot, "ForBrowser/BLURAY").mkdirs()

        testTitle = Title(name = "2012", sort_name = "2012")
        testTitle.save()

        val sourceFile = File(nasRoot, "BLURAY/2012.mkv")
        sourceFile.writeText("fake mkv content")

        testTranscode = Transcode(
            title_id = testTitle.id!!,
            file_path = sourceFile.absolutePath,
            file_size_bytes = sourceFile.length(),
            media_format = MediaFormat.BLURAY.name,
            status = TranscodeStatus.COMPLETE.name
        )
        testTranscode.save()
    }

    @After
    fun teardown() {
        TranscodeLease.deleteAll()
        ForBrowserProbeStream.deleteAll()
        ForBrowserProbe.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        nasRoot.deleteRecursively()
    }

    @Test
    fun `reclassify moves file and updates transcode record`() {
        val result = ReclassifyService.reclassify(
            testTranscode.id!!, MediaFormat.UHD_BLURAY, nasRoot.absolutePath
        )

        assertEquals(MediaFormat.BLURAY.name, result.oldFormat)
        assertEquals(MediaFormat.UHD_BLURAY.name, result.newFormat)
        assertTrue(result.oldPath.contains("BLURAY/2012.mkv"))
        assertTrue(result.newPath.contains("UHD/2012.mkv"))

        // Source gone, target exists
        assertFalse(File(nasRoot, "BLURAY/2012.mkv").exists())
        assertTrue(File(nasRoot, "UHD/2012.mkv").exists())

        // DB record updated
        val updated = Transcode.findById(testTranscode.id!!)!!
        assertTrue(updated.file_path!!.contains("UHD"))
        assertEquals(MediaFormat.UHD_BLURAY.name, updated.media_format)
        assertNotNull(updated.file_size_bytes)
    }

    @Test
    fun `reclassify deletes old ForBrowser output`() {
        val forBrowserFile = File(nasRoot, "ForBrowser/BLURAY/2012.mp4")
        forBrowserFile.writeText("fake mp4")

        val result = ReclassifyService.reclassify(
            testTranscode.id!!, MediaFormat.UHD_BLURAY, nasRoot.absolutePath
        )

        assertTrue(result.forBrowserDeleted)
        assertFalse(forBrowserFile.exists())
    }

    @Test
    fun `reclassify deletes ForBrowser probe data`() {
        ForBrowserProbeService.recordProbe(
            transcodeId = testTranscode.id!!,
            relativePath = "BLURAY/2012.mp4",
            probeResult = ForBrowserProbeResult(
                durationSecs = 158.0,
                streams = listOf(
                    StreamInfo(0, "video", "h264", 1920, 1080, rawLine = "video"),
                    StreamInfo(1, "audio", "aac", channels = 2, rawLine = "audio")
                ),
                rawOutput = "probe output"
            ),
            encoder = "libx264",
            fileSize = 5_000_000L
        )

        assertTrue(ForBrowserProbeService.hasProbe(testTranscode.id!!))

        ReclassifyService.reclassify(
            testTranscode.id!!, MediaFormat.DVD, nasRoot.absolutePath
        )

        assertFalse(ForBrowserProbeService.hasProbe(testTranscode.id!!))
    }

    @Test
    fun `reclassify clears failed leases`() {
        val lease = TranscodeLease(
            transcode_id = testTranscode.id!!,
            buddy_name = "test-buddy",
            relative_path = "BLURAY/2012.mkv",
            file_size_bytes = 100,
            claimed_at = LocalDateTime.now().minusHours(1),
            expires_at = LocalDateTime.now().minusMinutes(30),
            status = LeaseStatus.FAILED.name,
            error_message = "codec error"
        )
        lease.save()

        val result = ReclassifyService.reclassify(
            testTranscode.id!!, MediaFormat.UHD_BLURAY, nasRoot.absolutePath
        )

        assertEquals(1, result.leasesCleared)
        val remaining = TranscodeLease.findAll()
            .filter { it.transcode_id == testTranscode.id }
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `reclassify rejects same format`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ReclassifyService.reclassify(
                testTranscode.id!!, MediaFormat.BLURAY, nasRoot.absolutePath
            )
        }
        assertTrue(ex.message!!.contains("already"))
    }

    @Test
    fun `reclassify rejects missing source file`() {
        File(nasRoot, "BLURAY/2012.mkv").delete()

        val ex = assertFailsWith<IllegalStateException> {
            ReclassifyService.reclassify(
                testTranscode.id!!, MediaFormat.UHD_BLURAY, nasRoot.absolutePath
            )
        }
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `reclassify rejects if target file already exists`() {
        File(nasRoot, "UHD/2012.mkv").writeText("already here")

        val ex = assertFailsWith<IllegalStateException> {
            ReclassifyService.reclassify(
                testTranscode.id!!, MediaFormat.UHD_BLURAY, nasRoot.absolutePath
            )
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `reclassify rejects nonexistent transcode`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ReclassifyService.reclassify(99999, MediaFormat.UHD_BLURAY, nasRoot.absolutePath)
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun `formatToDirectory maps correctly`() {
        assertEquals("BLURAY", ReclassifyService.formatToDirectory(MediaFormat.BLURAY))
        assertEquals("DVD", ReclassifyService.formatToDirectory(MediaFormat.DVD))
        assertEquals("UHD", ReclassifyService.formatToDirectory(MediaFormat.UHD_BLURAY))
        assertFailsWith<IllegalArgumentException> {
            ReclassifyService.formatToDirectory(MediaFormat.HD_DVD)
        }
    }
}
