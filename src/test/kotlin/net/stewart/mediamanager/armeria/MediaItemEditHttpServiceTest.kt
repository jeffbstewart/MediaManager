package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleAuthor
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class MediaItemEditHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("mediaitemedit") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = MediaItemEditHttpService()

    @Before
    fun reset() {
        AmazonOrder.deleteAll()
        TitleAuthor.deleteAll()
        Author.deleteAll()
        BookSeries.deleteAll()
        // MediaItemTitleSeason FKs MediaItemTitle and TitleSeason FKs Title —
        // drop the leaves first.
        MediaItemTitleSeason.deleteAll()
        TitleSeason.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedItemWithTitle(
        format: MediaFormat = MediaFormat.BLURAY,
        titleName: String = "Movie",
        mediaType: MediaType = MediaType.MOVIE,
    ): Pair<MediaItem, Title> {
        val item = MediaItem(
            media_format = format.name,
            product_name = titleName,
            upc = "012345678901",
        ).apply { save() }
        val title = Title(
            name = titleName,
            media_type = mediaType.name,
            sort_name = titleName.lowercase(),
        ).apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!,
            seasons = null).save()
        return item to title
    }

    // ---------------------- getItem ----------------------

    @Test
    fun `getItem returns 401 unauthenticated`() {
        val resp = service.getItem(
            ctxFor("/api/v2/admin/media-item/1", user = null), itemId = 1L
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `getItem returns 403 for non-admin viewers`() {
        val resp = service.getItem(
            ctxFor("/api/v2/admin/media-item/1",
                user = getOrCreateUser("viewer", level = 1)),
            itemId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `getItem returns 404 when itemId does not exist`() {
        val resp = service.getItem(
            ctxFor("/api/v2/admin/media-item/9999",
                user = getOrCreateUser("admin", level = 2)),
            itemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `getItem returns the full edit-page payload for a movie`() {
        val (item, title) = seedItemWithTitle()
        val resp = service.getItem(
            ctxFor("/api/v2/admin/media-item/${item.id}",
                user = getOrCreateUser("admin", level = 2)),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(item.id, body.get("media_item_id").asLong)
        assertEquals(MediaFormat.BLURAY.name, body.get("media_format").asString)
        val titles = body.getAsJsonArray("titles")
        assertEquals(1, titles.size())
        assertEquals(title.id, titles[0].asJsonObject.get("title_id").asLong)
    }

    @Test
    fun `getItem surfaces book authors and series for book titles`() {
        val (item, title) = seedItemWithTitle(format = MediaFormat.EBOOK_EPUB,
            titleName = "Book", mediaType = MediaType.BOOK)
        val author = Author(name = "An Author", sort_name = "an author").apply { save() }
        TitleAuthor(title_id = title.id!!, author_id = author.id!!,
            author_order = 0).save()
        val series = BookSeries(name = "Series Name").apply { save() }
        val refreshed = Title.findById(title.id!!)!!.apply {
            book_series_id = series.id
            save()
        }

        val resp = service.getItem(
            ctxFor("/api/v2/admin/media-item/${item.id}",
                user = getOrCreateUser("admin", level = 2)),
            itemId = item.id!!,
        )
        val body = readJsonObject(resp)
        assertEquals(1, body.getAsJsonArray("authors").size())
        assertEquals("An Author",
            body.getAsJsonArray("authors")[0].asJsonObject.get("name").asString)
        assertEquals("Series Name", body.getAsJsonObject("book_series").get("name").asString)
    }

    // ---------------------- setMediaType ----------------------

    @Test
    fun `setMediaType returns 401 unauthenticated`() {
        val resp = service.setMediaType(
            ctxFor("/api/v2/admin/media-item/1/media-type",
                method = HttpMethod.POST, user = null,
                jsonBody = """{"media_type": "MOVIE"}"""),
            itemId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `setMediaType returns 404 when item is missing`() {
        val resp = service.setMediaType(
            ctxFor("/api/v2/admin/media-item/9999/media-type",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_type": "MOVIE"}"""),
            itemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `setMediaType returns 400 when media_type is missing`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.setMediaType(
            ctxFor("/api/v2/admin/media-item/${item.id}/media-type",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setMediaType updates the linked Title's media_type`() {
        val (item, title) = seedItemWithTitle(mediaType = MediaType.MOVIE)
        val resp = service.setMediaType(
            ctxFor("/api/v2/admin/media-item/${item.id}/media-type",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_type": "TV"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals("TV", Title.findById(title.id!!)!!.media_type)
    }

    @Test
    fun `setMediaType returns 400 when item has no linked title`() {
        val item = MediaItem(media_format = MediaFormat.BLURAY.name,
            product_name = "Orphan").apply { save() }
        val resp = service.setMediaType(
            ctxFor("/api/v2/admin/media-item/${item.id}/media-type",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_type": "TV"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // ---------------------- setMediaFormat ----------------------

    @Test
    fun `setMediaFormat returns 400 when media_format is missing`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.setMediaFormat(
            ctxFor("/api/v2/admin/media-item/${item.id}/format",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setMediaFormat rejects a format that is invalid for the title's media_type`() {
        // Movie items shouldn't be able to switch to a book format.
        val (item, _) = seedItemWithTitle(mediaType = MediaType.MOVIE,
            format = MediaFormat.BLURAY)
        val resp = service.setMediaFormat(
            ctxFor("/api/v2/admin/media-item/${item.id}/format",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_format": "EBOOK_EPUB"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setMediaFormat persists a valid format swap`() {
        val (item, _) = seedItemWithTitle(mediaType = MediaType.MOVIE,
            format = MediaFormat.DVD)
        val resp = service.setMediaFormat(
            ctxFor("/api/v2/admin/media-item/${item.id}/format",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_format": "BLURAY"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(MediaFormat.BLURAY.name,
            MediaItem.findById(item.id!!)!!.media_format)
    }

    // ---------------------- setSeasons ----------------------

    @Test
    fun `setSeasons returns 400 when join_id is missing`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.setSeasons(
            ctxFor("/api/v2/admin/media-item/${item.id}/seasons",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"seasons": "1-3"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setSeasons returns 404 when join_id does not exist`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.setSeasons(
            ctxFor("/api/v2/admin/media-item/${item.id}/seasons",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"join_id": 9999, "seasons": "1-3"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `setSeasons returns 403 when join belongs to a different media item`() {
        val (item, _) = seedItemWithTitle()
        val (otherItem, _) = seedItemWithTitle(titleName = "Other")
        val otherJoin = MediaItemTitle.findAll()
            .single { it.media_item_id == otherItem.id }

        val resp = service.setSeasons(
            ctxFor("/api/v2/admin/media-item/${item.id}/seasons",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"join_id": ${otherJoin.id}, "seasons": "1"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `setSeasons returns 400 for an unparseable seasons string`() {
        val (item, _) = seedItemWithTitle()
        val join = MediaItemTitle.findAll().single { it.media_item_id == item.id }
        val resp = service.setSeasons(
            ctxFor("/api/v2/admin/media-item/${item.id}/seasons",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"join_id": ${join.id}, "seasons": "garbage"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setSeasons persists a valid seasons string`() {
        val (item, _) = seedItemWithTitle()
        val join = MediaItemTitle.findAll().single { it.media_item_id == item.id }
        val resp = service.setSeasons(
            ctxFor("/api/v2/admin/media-item/${item.id}/seasons",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"join_id": ${join.id}, "seasons": "1-3"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals("1-3", MediaItemTitle.findById(join.id!!)!!.seasons)
    }

    // ---------------------- assignTmdb ----------------------

    @Test
    fun `assignTmdb returns 400 when tmdb_id is missing`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.assignTmdb(
            ctxFor("/api/v2/admin/media-item/${item.id}/assign-tmdb",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"media_type": "MOVIE"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `assignTmdb returns 400 when media_type is missing`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.assignTmdb(
            ctxFor("/api/v2/admin/media-item/${item.id}/assign-tmdb",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"tmdb_id": 99}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `assignTmdb returns 400 when item has no linked title`() {
        val item = MediaItem(media_format = MediaFormat.BLURAY.name,
            product_name = "Orphan").apply { save() }
        val resp = service.assignTmdb(
            ctxFor("/api/v2/admin/media-item/${item.id}/assign-tmdb",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"tmdb_id": 1, "media_type": "MOVIE"}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // ---------------------- searchTmdb (auth gates only) ----------------------

    @Test
    fun `searchTmdb returns 403 for non-admin viewers`() {
        val resp = service.searchTmdb(
            ctxFor("/api/v2/admin/media-item/search-tmdb?q=x",
                user = getOrCreateUser("viewer", level = 1)),
            query = "x", type = "MOVIE",
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    // ---------------------- updatePurchase ----------------------

    @Test
    fun `updatePurchase returns 404 when item is missing`() {
        val resp = service.updatePurchase(
            ctxFor("/api/v2/admin/media-item/9999/purchase",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"purchase_place": "Amazon"}"""),
            itemId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `updatePurchase persists multiple fields in a single call`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.updatePurchase(
            ctxFor("/api/v2/admin/media-item/${item.id}/purchase",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{
                    "purchase_place": "Best Buy",
                    "storage_location": "shelf-3",
                    "purchase_date": "2024-12-01",
                    "purchase_price": 19.99
                }"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshed = MediaItem.findById(item.id!!)!!
        assertEquals("Best Buy", refreshed.purchase_place)
        assertEquals("shelf-3", refreshed.storage_location)
        assertEquals(LocalDate.of(2024, 12, 1), refreshed.purchase_date)
        assertEquals(BigDecimal.valueOf(19.99), refreshed.purchase_price)
    }

    @Test
    fun `updatePurchase clears values when fields are explicitly blank`() {
        val (item, _) = seedItemWithTitle()
        // Pre-populate the fields.
        val refreshed = MediaItem.findById(item.id!!)!!.apply {
            purchase_place = "Old Store"
            storage_location = "old-shelf"
            updated_at = LocalDateTime.now()
            save()
        }
        val resp = service.updatePurchase(
            ctxFor("/api/v2/admin/media-item/${item.id}/purchase",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"purchase_place": "", "storage_location": ""}"""),
            itemId = item.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val cleared = MediaItem.findById(item.id!!)!!
        assertEquals(null, cleared.purchase_place)
        assertEquals(null, cleared.storage_location)
    }

    // ---------------------- searchAmazonOrders ----------------------

    @Test
    fun `searchAmazonOrders returns 404 when item is missing`() {
        val resp = service.searchAmazonOrders(
            ctxFor("/api/v2/admin/media-item/9999/amazon-orders?q=x",
                user = getOrCreateUser("admin", level = 2)),
            itemId = 9999L, query = "x",
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `searchAmazonOrders returns the orders + the resolved search_query`() {
        val (item, _) = seedItemWithTitle()
        val resp = service.searchAmazonOrders(
            ctxFor("/api/v2/admin/media-item/${item.id}/amazon-orders?q=Mov",
                user = getOrCreateUser("admin", level = 2)),
            itemId = item.id!!, query = "Mov",
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals("Mov", body.get("search_query").asString)
        assertTrue(body.has("orders"))
    }

    // ---------------------- linkAmazonOrder ----------------------

    @Test
    fun `linkAmazonOrder returns 404 when item is missing`() {
        val resp = service.linkAmazonOrder(
            ctxFor("/api/v2/admin/media-item/9999/link-amazon/1",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            itemId = 9999L, orderId = 1L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `linkAmazonOrder returns 403 for viewers`() {
        val resp = service.linkAmazonOrder(
            ctxFor("/api/v2/admin/media-item/1/link-amazon/1",
                method = HttpMethod.POST,
                user = getOrCreateUser("viewer", level = 1)),
            itemId = 1L, orderId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }
}
