package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.service.FakeOpenLibraryService
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class AuthorHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("author") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = AuthorHttpService(openLibrary = FakeOpenLibraryService())

    @Before
    fun reset() {
        WishListItem.deleteAll()
        TitleAuthor.deleteAll()
        Title.deleteAll()
        BookSeries.deleteAll()
        Author.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `list returns 401 unauthenticated`() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            statusOf(service.list(ctxFor("/api/v2/catalog/authors", user = null))))
    }

    @Test
    fun `list returns authors sorted by sort_name with book_count`() {
        val admin = getOrCreateUser("admin", level = 2)
        val zZ = Author(name = "Zane", sort_name = "zane").apply { save() }
        val aa = Author(name = "Asimov", sort_name = "asimov").apply { save() }
        val book = Title(name = "Foundation", media_type = MediaType.BOOK.name,
            sort_name = "foundation").apply { save() }
        TitleAuthor(title_id = book.id!!, author_id = aa.id!!,
            author_order = 0).save()

        val resp = service.list(ctxFor("/api/v2/catalog/authors", user = admin))
        val authors = readJsonObject(resp).getAsJsonArray("authors")
        assertEquals(2, authors.size())
        assertEquals("Asimov", authors[0].asJsonObject.get("name").asString)
        assertEquals(1, authors[0].asJsonObject.get("book_count").asInt)
        assertEquals("Zane", authors[1].asJsonObject.get("name").asString)
        assertEquals(0, authors[1].asJsonObject.get("book_count").asInt)
    }

    @Test
    fun `detail returns 401 unauthenticated`() {
        val resp = service.detail(ctxFor("/api/v2/catalog/authors/1", user = null),
            authorId = 1L)
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `detail returns 404 when author is missing`() {
        val resp = service.detail(
            ctxFor("/api/v2/catalog/authors/9999",
                user = getOrCreateUser("admin", level = 2)),
            authorId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `detail returns the author plus owned books`() {
        val admin = getOrCreateUser("admin", level = 2)
        val author = Author(name = "Asimov", sort_name = "asimov",
            biography = "SF master").apply { save() }
        val book = Title(name = "Foundation", media_type = MediaType.BOOK.name,
            sort_name = "foundation",
            first_publication_year = 1951).apply { save() }
        TitleAuthor(title_id = book.id!!, author_id = author.id!!,
            author_order = 0).save()

        val resp = service.detail(
            ctxFor("/api/v2/catalog/authors/${author.id}", user = admin),
            authorId = author.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals("Asimov", body.get("name").asString)
        assertEquals("SF master", body.get("biography").asString)
        val owned = body.getAsJsonArray("owned_books")
        assertEquals(1, owned.size())
        assertEquals("Foundation", owned[0].asJsonObject.get("title_name").asString)
        // OL author id is null → other_works should be empty.
        assertEquals(0, body.getAsJsonArray("other_works").size())
    }
}
