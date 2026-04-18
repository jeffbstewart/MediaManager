package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [WishListService] book-wish paths (M3).
 * Covers add idempotency, resurrection of cancelled wishes, activeBookWishWorkIdsForUser,
 * and auto-fulfillment triggered by [BookIngestionService].
 */
class WishListServiceBookTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:wishbooktest;DB_CLOSE_DELAY=-1"
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

    private var userId: Long = 0

    @Before
    fun setup() {
        WishListItem.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        TitleAuthor.deleteAll()
        Title.deleteAll()
        BookSeries.deleteAll()
        Author.deleteAll()
        AppUser.deleteAll()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x", access_level = 1)
        u.save()
        userId = u.id!!
    }

    private fun input(workId: String = "OL1W") = WishListService.BookWishInput(
        openLibraryWorkId = workId,
        title = "Foundation",
        author = "Isaac Asimov",
        coverIsbn = "0553293354",
        seriesId = null,
        seriesNumber = null
    )

    @Test
    fun `addBookWish is idempotent`() {
        val a = WishListService.addBookWishForUser(userId, input())
        val b = WishListService.addBookWishForUser(userId, input())
        assertEquals(a.id, b.id)
        assertEquals(1, WishListItem.findAll().size)
    }

    @Test
    fun `addBookWish resurrects a cancelled wish`() {
        val a = WishListService.addBookWishForUser(userId, input())
        assertTrue(WishListService.removeBookWishForUser(userId, "OL1W"))
        assertEquals(WishStatus.CANCELLED.name, WishListItem.findById(a.id!!)!!.status)

        val b = WishListService.addBookWishForUser(userId, input())
        assertEquals(a.id, b.id, "Should reuse the same row, not insert a duplicate")
        assertEquals(WishStatus.ACTIVE.name, b.status)
        assertEquals(1, WishListItem.findAll().size)
    }

    @Test
    fun `activeBookWishWorkIdsForUser returns only active BOOK wishes`() {
        WishListService.addBookWishForUser(userId, input("OL1W"))
        WishListService.addBookWishForUser(userId, input("OL2W"))
        WishListService.removeBookWishForUser(userId, "OL2W")

        val active = WishListService.activeBookWishWorkIdsForUser(userId)
        assertEquals(setOf("OL1W"), active)
    }

    @Test
    fun `removeBookWish returns false when no active wish`() {
        assertTrue(!WishListService.removeBookWishForUser(userId, "OL-NONE"))
    }

    @Test
    fun `fulfillBookWishes marks active wishes FULFILLED`() {
        val wish = WishListService.addBookWishForUser(userId, input("OL-FND"))
        WishListService.fulfillBookWishes("OL-FND")

        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.FULFILLED.name, refreshed.status)
        assertNotNull(refreshed.fulfilled_at)
    }

    @Test
    fun `BookIngestionService fulfills matching wishes on scan`() {
        WishListService.addBookWishForUser(userId, input("OL-FND"))

        val lookup = OpenLibraryBookLookup(
            openLibraryWorkId = "OL-FND",
            openLibraryEditionId = "OLeditionM",
            workTitle = "Foundation",
            isbn = "0553293354",
            rawPhysicalFormat = "Paperback",
            mediaFormat = MediaFormat.TRADE_PAPERBACK.name,
            pageCount = 244,
            editionYear = 1991,
            firstPublicationYear = 1951,
            description = "Asimov's classic.",
            coverUrl = null,
            authors = listOf(OpenLibraryAuthor("OL34184A", "Isaac Asimov")),
            series = emptyList(),
            rawJson = "{}"
        )

        BookIngestionService.ingest("0553293354", lookup)

        val fulfilled = WishListItem.findAll().single()
        assertEquals(WishStatus.FULFILLED.name, fulfilled.status)
        assertNotNull(fulfilled.fulfilled_at)
    }
}
