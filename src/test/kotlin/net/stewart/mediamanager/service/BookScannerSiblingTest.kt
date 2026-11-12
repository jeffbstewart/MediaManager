package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration coverage for the sibling auto-link added in response to the
 * "I have Foundation.epub and Foundation.pdf" case. Exercises:
 *
 * - [BookScannerAgent.findSiblingTitle] — given a new file, locate an
 *   already-catalogued MediaItem in the same directory with the same
 *   basename + different extension, and return its Title.
 * - [BookScannerAgent.extensionPriority] — orders EPUB before PDF so
 *   the sibling exists by the time we look for it.
 * - [BookIngestionService.linkDigitalEditionToTitle] — the cheap
 *   edition-linking path that skips OL.
 */
class BookScannerSiblingTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:booksibling;DB_CLOSE_DELAY=-1"
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

    @Before
    fun cleanup() {
        UnmatchedBook.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        TitleAuthor.deleteAll()
        Title.deleteAll()
        BookSeries.deleteAll()
        Author.deleteAll()
        AppUser.deleteAll()
    }

    private fun stageCatalogedEpub(path: String, titleName: String = "Foundation"): Title {
        val now = LocalDateTime.now()
        val title = Title(
            name = titleName,
            media_type = MediaType.BOOK.name,
            sort_name = titleName.lowercase(),
            open_library_work_id = "OL1W",
            created_at = now, updated_at = now
        ).also { it.save() }
        val item = MediaItem(
            media_format = MediaFormat.EBOOK_EPUB.name,
            title_count = 1,
            file_path = path,
            product_name = titleName,
            created_at = now, updated_at = now
        ).also { it.save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()
        return title
    }

    @Test
    fun `findSiblingTitle matches same directory + basename + different extension`() {
        val dir = Files.createTempDirectory("sibling-")
        val epub = dir.resolve("Foundation.epub").toFile().apply { createNewFile() }
        val pdf = dir.resolve("Foundation.pdf").toFile().apply { createNewFile() }
        try {
            val staged = stageCatalogedEpub(epub.absolutePath)
            val agent = BookScannerAgent()

            val hit = agent.findSiblingTitle(pdf)
            assertNotNull(hit)
            assertEquals(staged.id, hit.id)
        } finally {
            epub.delete(); pdf.delete(); Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `findSiblingTitle is case-insensitive on basename`() {
        val dir = Files.createTempDirectory("sibling-")
        val epub = dir.resolve("Foundation.epub").toFile().apply { createNewFile() }
        val pdf = dir.resolve("FOUNDATION.PDF").toFile().apply { createNewFile() }
        try {
            val staged = stageCatalogedEpub(epub.absolutePath)
            val hit = BookScannerAgent().findSiblingTitle(pdf)
            assertNotNull(hit)
            assertEquals(staged.id, hit.id)
        } finally {
            epub.delete(); pdf.delete(); Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `findSiblingTitle returns null when no match`() {
        val dir = Files.createTempDirectory("sibling-")
        val orphan = dir.resolve("UnrelatedBook.pdf").toFile().apply { createNewFile() }
        try {
            // No catalogued MediaItem at all.
            val agent = BookScannerAgent()
            assertNull(agent.findSiblingTitle(orphan))
        } finally {
            orphan.delete(); Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `findSiblingTitle does not match a file in a different directory`() {
        val dirA = Files.createTempDirectory("sibling-a-")
        val dirB = Files.createTempDirectory("sibling-b-")
        val epub = dirA.resolve("Foundation.epub").toFile().apply { createNewFile() }
        val pdf = dirB.resolve("Foundation.pdf").toFile().apply { createNewFile() }
        try {
            stageCatalogedEpub(epub.absolutePath)
            assertNull(BookScannerAgent().findSiblingTitle(pdf))
        } finally {
            epub.delete(); pdf.delete(); Files.deleteIfExists(dirA); Files.deleteIfExists(dirB)
        }
    }

    @Test
    fun `findSiblingTitle ignores same-extension collisions`() {
        val dir = Files.createTempDirectory("sibling-")
        val epub = dir.resolve("Foundation.epub").toFile().apply { createNewFile() }
        val other = dir.resolve("Foundation2.epub").toFile().apply { createNewFile() }
        try {
            stageCatalogedEpub(epub.absolutePath)
            // Same extension → not a sibling (a new EPUB with same basename
            // would normally be the identical work, but with different
            // basename it's unrelated).
            assertNull(BookScannerAgent().findSiblingTitle(other))
        } finally {
            epub.delete(); other.delete(); Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `linkDigitalEditionToTitle creates a new MediaItem linked to the title`() {
        val title = stageCatalogedEpub("/tmp/Foundation.epub")
        val before = MediaItem.findAll().size
        val before_links = MediaItemTitle.findAll().size

        val result = BookIngestionService.linkDigitalEditionToTitle(
            filePath = "/tmp/Foundation.pdf",
            fileFormat = MediaFormat.EBOOK_PDF,
            title = title
        )

        assertEquals(title.id, result.title.id)
        assertEquals(true, result.titleReused)
        assertEquals(MediaFormat.EBOOK_PDF.name, result.mediaItem.media_format)
        assertEquals("/tmp/Foundation.pdf", result.mediaItem.file_path)

        assertEquals(before + 1, MediaItem.findAll().size)
        assertEquals(before_links + 1, MediaItemTitle.findAll().size)
    }

    @Test
    fun `extensionPriority orders EPUB before PDF`() {
        val agent = BookScannerAgent()
        val exts = listOf("pdf", "epub", "pdf").sortedBy { agent.extensionPriority(it) }
        assertEquals(listOf("epub", "pdf", "pdf"), exts)
    }
}
