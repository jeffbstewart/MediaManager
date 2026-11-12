package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.Title
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [BackdropTintBackfill] against an in-memory H2 catalog and backdrop files
 * in a temp directory. The job is stopped mid-batch and restarted to show
 * every row is either fully done or untouched, that it resumes where it
 * stopped, and that it does no double work.
 */
class BackdropTintBackfillTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:tintbackfill;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
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

    private lateinit var dir: Path
    private val files = mutableMapOf<String, Path>()
    private val clock = TestClock()

    @Before
    fun setUp() {
        Title.deleteAll()
        dir = Files.createTempDirectory("tint-backfill")
        files.clear()
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun jpeg(cacheId: String, color: Color): Path {
        val img = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 36) for (x in 0 until 64) img.setRGB(x, y, color.rgb)
        val path = dir.resolve("$cacheId.jpg")
        ImageIO.write(img, "jpg", path.toFile())
        files[cacheId] = path
        return path
    }

    private fun title(name: String, backdropPath: String?, cacheId: String?): Title {
        val now = LocalDateTime.now()
        return Title(name = name, sort_name = name.lowercase(), backdrop_path = backdropPath,
            backdrop_cache_id = cacheId, created_at = now, updated_at = now).apply { save() }
    }

    private fun job(batchSize: Int = 2) = BackdropTintBackfill(
        resolveBackdrop = { t -> t.backdrop_cache_id?.let { files[it] } },
        clock = clock,
        batchSize = batchSize,
        pauseBetweenBatches = 1.seconds,
    )

    private fun tintOf(id: Long): String? = Title.findById(id)!!.backdrop_tint

    @Test
    fun `stopping mid-batch leaves rows done or untouched, and a restart finishes without double work`() {
        jpeg("c1", Color(220, 30, 40))
        jpeg("c2", Color(20, 60, 200))
        jpeg("c3", Color(30, 170, 60))
        jpeg("c4", Color(240, 180, 20))
        jpeg("c5", Color(150, 20, 200))
        val withFile = (1..5).map { title("T$it", "/b$it.jpg", "c$it") }
        val missingFile = title("Missing", "/missing.jpg", "gone")
        val noBackdrop = title("Plain", null, null)

        var seen = 0
        val first = job()
        val firstDone = first.run(onTitleDone = { if (++seen == 3) first.stop() })
        assertEquals(3, firstDone)

        val all = withFile + missingFile
        val tinted = all.filter { tintOf(it.id!!) != null }
        assertEquals(3, tinted.size, "exactly the three claimed rows carry a tint")
        assertEquals(3, first.pendingCount())
        assertNull(tintOf(noBackdrop.id!!))
        for (t in tinted) {
            val path = files[t.backdrop_cache_id] ?: continue
            assertEquals(tintOf(t.id!!), MetadataWriter.readSidecar(path)?.tint, "sidecar matches the row for ${t.name}")
        }

        val second = job()
        assertEquals(3, second.run())
        assertEquals(0, second.pendingCount())
        for (t in withFile) {
            val tint = tintOf(t.id!!)
            assertNotNull(tint, t.name)
            assertNotEquals(BackdropTint.FALLBACK, tint, "${t.name} decoded")
            assertEquals(tint, MetadataWriter.readSidecar(files[t.backdrop_cache_id]!!)?.tint)
        }
        assertEquals(BackdropTint.FALLBACK, tintOf(missingFile.id!!), "a missing file converges on the fallback")
        assertNull(tintOf(noBackdrop.id!!), "a title without a backdrop is never touched")

        // Hue sanity on one row: the red backdrop's tint is red.
        val red = tintOf(withFile[0].id!!)!!
        assertTrue(red.substring(1, 3).toInt(16) > red.substring(3, 5).toInt(16))

        assertEquals(0, job().run(), "nothing left: no double work")
        assertTrue(clock.sleepRequests.isNotEmpty(), "the job paused between full batches")
    }

    @Test
    fun `an existing sidecar keeps its fields and gains the tint`() {
        val path = jpeg("keep", Color(20, 60, 200))
        val t = title("Keep", "/keep.jpg", "keep")
        MetadataWriter.writeSidecar(path, ImageMetadata.internet(
            provider = InternetImageStore.Provider.TMDB_BACKDROP, cacheKey = "keep",
            upstreamUrl = "https://image.tmdb.org/t/p/w1280/keep.jpg",
            subjectType = "title", subjectId = t.id, contentType = "image/jpeg"))

        assertEquals(1, job().run())

        val meta = MetadataWriter.readSidecar(path)!!
        assertEquals("https://image.tmdb.org/t/p/w1280/keep.jpg", meta.upstream_url)
        assertEquals(t.id, meta.subject_id)
        assertEquals(tintOf(t.id!!), meta.tint)
    }

    @Test
    fun `a backdrop that does not decode gets the fallback and is not retried`() {
        val bad = dir.resolve("bad.jpg")
        Files.writeString(bad, "not an image")
        files["bad"] = bad
        val t = title("Bad", "/bad.jpg", "bad")

        assertEquals(1, job().run())
        assertEquals(BackdropTint.FALLBACK, tintOf(t.id!!))
        assertEquals(0, job().run())
    }
}
