package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [WishListHttpService] — the SPA wishlist endpoints.
 * Drives every method through the [ArmeriaTestBase] context-builder
 * harness. The TMDB-backed `search` endpoint hits the live TMDB API
 * when called, so it's exercised only via the auth-gate path.
 */
internal class WishListHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("wishlist") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = WishListHttpService()

    @Before
    fun reset() {
        WishListItem.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    // ---------------------- /api/v2/wishlist (GET) ----------------------

    @Test
    fun `getWishList returns 401 unauthenticated`() {
        val resp = service.getWishList(ctxFor("/api/v2/wishlist", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `getWishList returns the empty-shape on an empty account`() {
        val resp = service.getWishList(ctxFor("/api/v2/wishlist",
            user = getOrCreateUser("admin", level = 2)))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        // All four lists exist and are empty.
        for (key in listOf("media_wishes", "transcode_wishes",
            "book_wishes", "album_wishes")) {
            assertTrue(body.has(key), "missing key '$key'")
            assertEquals(0, body.getAsJsonArray(key).size())
        }
        assertEquals(false, body.get("has_any_media_wish").asBoolean)
    }

    @Test
    fun `getWishList surfaces the user's media wishes with lifecycle metadata`() {
        val admin = getOrCreateUser("admin", level = 2)
        WishListItem(
            user_id = admin.id!!,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 12345,
            tmdb_title = "The Wishlisted",
            tmdb_media_type = "MOVIE",
            tmdb_release_year = 2024,
        ).save()

        val resp = service.getWishList(ctxFor("/api/v2/wishlist", user = admin))
        val body = readJsonObject(resp)
        val mediaWishes = body.getAsJsonArray("media_wishes")
        assertEquals(1, mediaWishes.size())
        val first = mediaWishes[0].asJsonObject
        assertEquals("The Wishlisted", first.get("tmdb_title").asString)
        assertEquals(12345, first.get("tmdb_id").asInt)
        assertTrue(first.has("lifecycle_stage"))
        assertEquals(true, body.get("has_any_media_wish").asBoolean)
    }

    @Test
    fun `getWishList surfaces book wishes`() {
        val admin = getOrCreateUser("admin", level = 2)
        WishListItem(
            user_id = admin.id!!,
            wish_type = WishType.BOOK.name,
            status = WishStatus.ACTIVE.name,
            open_library_work_id = "OL12345W",
            book_title = "Slaughterhouse-Five",
            book_author = "Kurt Vonnegut",
        ).save()

        val resp = service.getWishList(ctxFor("/api/v2/wishlist", user = admin))
        val books = readJsonObject(resp).getAsJsonArray("book_wishes")
        assertEquals(1, books.size())
        assertEquals("OL12345W", books[0].asJsonObject.get("ol_work_id").asString)
        assertEquals("Slaughterhouse-Five", books[0].asJsonObject.get("title").asString)
    }

    @Test
    fun `getWishList surfaces album wishes`() {
        val admin = getOrCreateUser("admin", level = 2)
        val rgId = java.util.UUID.randomUUID().toString()
        WishListItem(
            user_id = admin.id!!,
            wish_type = WishType.ALBUM.name,
            status = WishStatus.ACTIVE.name,
            musicbrainz_release_group_id = rgId,
            album_title = "Kind of Blue",
            album_primary_artist = "Miles Davis",
            album_year = 1959,
        ).save()

        val resp = service.getWishList(ctxFor("/api/v2/wishlist", user = admin))
        val albums = readJsonObject(resp).getAsJsonArray("album_wishes")
        assertEquals(1, albums.size())
        assertEquals(rgId, albums[0].asJsonObject.get("release_group_id").asString)
        assertEquals("Kind of Blue", albums[0].asJsonObject.get("title").asString)
    }

    // ---------------------- /api/v2/wishlist/books ----------------------

    @Test
    fun `addBookWish returns 400 when ol_work_id is missing`() {
        val resp = service.addBookWish(ctxFor("/api/v2/wishlist/books",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"title": "Some Book"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addBookWish returns 400 when title is missing`() {
        val resp = service.addBookWish(ctxFor("/api/v2/wishlist/books",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"ol_work_id": "OL12345W"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addBookWish persists the book wish on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.addBookWish(ctxFor("/api/v2/wishlist/books",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"ol_work_id": "OL99999W",
                            "title": "Cat's Cradle",
                            "author": "Vonnegut"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val saved = WishListItem.findAll().single()
        assertEquals("OL99999W", saved.open_library_work_id)
        assertEquals("Cat's Cradle", saved.book_title)
    }

    @Test
    fun `removeBookWish returns 401 unauthenticated`() {
        val resp = service.removeBookWish(
            ctxFor("/api/v2/wishlist/books/OL1W", method = HttpMethod.DELETE,
                user = null),
            olWorkId = "OL1W",
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `removeBookWish returns removed=true after deleting the row`() {
        val admin = getOrCreateUser("admin", level = 2)
        WishListItem(user_id = admin.id!!, wish_type = WishType.BOOK.name,
            status = WishStatus.ACTIVE.name,
            open_library_work_id = "OL77W", book_title = "Bye").save()

        val resp = service.removeBookWish(
            ctxFor("/api/v2/wishlist/books/OL77W",
                method = HttpMethod.DELETE, user = admin),
            olWorkId = "OL77W",
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("removed").asBoolean)
    }

    // ---------------------- /api/v2/wishlist/albums ----------------------

    @Test
    fun `addAlbumWish returns 400 when release_group_id is missing`() {
        val resp = service.addAlbumWish(ctxFor("/api/v2/wishlist/albums",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"title": "X"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addAlbumWish returns 400 when title is missing`() {
        val resp = service.addAlbumWish(ctxFor("/api/v2/wishlist/albums",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"release_group_id": "${java.util.UUID.randomUUID()}"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addAlbumWish persists the album wish on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val rgId = java.util.UUID.randomUUID().toString()
        val resp = service.addAlbumWish(ctxFor("/api/v2/wishlist/albums",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"release_group_id": "$rgId",
                            "title": "Bitches Brew",
                            "primary_artist": "Miles Davis",
                            "year": 1970,
                            "is_compilation": false}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val saved = WishListItem.findAll().single()
        assertEquals(rgId, saved.musicbrainz_release_group_id)
        assertEquals(1970, saved.album_year)
    }

    @Test
    fun `removeAlbumWish returns removed=true after deleting the row`() {
        val admin = getOrCreateUser("admin", level = 2)
        val rgId = java.util.UUID.randomUUID().toString()
        WishListItem(user_id = admin.id!!, wish_type = WishType.ALBUM.name,
            status = WishStatus.ACTIVE.name,
            musicbrainz_release_group_id = rgId,
            album_title = "Gone").save()

        val resp = service.removeAlbumWish(
            ctxFor("/api/v2/wishlist/albums/$rgId",
                method = HttpMethod.DELETE, user = admin),
            releaseGroupId = rgId,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("removed").asBoolean)
    }

    // ---------------------- /api/v2/wishlist/search ----------------------

    @Test
    fun `search returns 401 unauthenticated`() {
        val resp = service.search(ctxFor("/api/v2/wishlist/search?q=x", user = null),
            query = "x")
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    // ---------------------- /api/v2/wishlist/add ----------------------

    @Test
    fun `addWish returns 400 when tmdb_id is missing`() {
        val resp = service.addWish(ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"media_type": "MOVIE", "title": "x"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addWish returns 400 when media_type is missing`() {
        val resp = service.addWish(ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"tmdb_id": 1, "title": "x"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addWish returns 400 for an unknown media_type`() {
        val resp = service.addWish(ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST,
            user = getOrCreateUser("admin", level = 2),
            jsonBody = """{"tmdb_id": 1, "media_type": "PODCAST", "title": "x"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addWish persists a movie wish on the happy path`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.addWish(ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"tmdb_id": 99, "media_type": "MOVIE",
                            "title": "Dune"}"""))
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(true, body.get("ok").asBoolean)
        assertNotNull(body.get("id"))
    }

    @Test
    fun `addWish twice for the same tmdb id returns ok=false on the second call`() {
        val admin = getOrCreateUser("admin", level = 2)
        val req = ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"tmdb_id": 42, "media_type": "MOVIE", "title": "Dup"}""")
        val first = service.addWish(req)
        assertEquals(true, readJsonObject(first).get("ok").asBoolean)
        val second = service.addWish(ctxFor("/api/v2/wishlist/add",
            method = HttpMethod.POST, user = admin,
            jsonBody = """{"tmdb_id": 42, "media_type": "MOVIE", "title": "Dup"}"""))
        val body = readJsonObject(second)
        assertEquals(false, body.get("ok").asBoolean)
        assertEquals("Already on wish list", body.get("reason").asString)
    }

    // ---------------------- /api/v2/wishlist/{wishId} (DELETE) ----------------------

    @Test
    fun `cancelWish returns 401 unauthenticated`() {
        val resp = service.cancelWish(
            ctxFor("/api/v2/wishlist/1", method = HttpMethod.DELETE, user = null),
            wishId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `cancelWish marks the wish as cancelled`() {
        val admin = getOrCreateUser("admin", level = 2)
        val wish = WishListItem(
            user_id = admin.id!!, wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 7, tmdb_title = "Cancel me", tmdb_media_type = "MOVIE",
        ).apply { save() }

        val resp = service.cancelWish(
            ctxFor("/api/v2/wishlist/${wish.id}",
                method = HttpMethod.DELETE, user = admin),
            wishId = wish.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        // The status is mutated by the service; reload to inspect.
        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.CANCELLED.name, refreshed.status)
    }

    // ---------------------- /api/v2/wishlist/{wishId}/dismiss ----------------------

    @Test
    fun `dismissWish returns 404 when the wish belongs to another user`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val wish = WishListItem(
            user_id = owner.id!!, wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 1, tmdb_title = "Mine", tmdb_media_type = "MOVIE",
        ).apply { save() }

        val resp = service.dismissWish(
            ctxFor("/api/v2/wishlist/${wish.id}/dismiss",
                method = HttpMethod.POST, user = other),
            wishId = wish.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `dismissWish flips status to DISMISSED for the owning user`() {
        val admin = getOrCreateUser("admin", level = 2)
        val wish = WishListItem(
            user_id = admin.id!!, wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 1, tmdb_title = "Done", tmdb_media_type = "MOVIE",
        ).apply { save() }

        val resp = service.dismissWish(
            ctxFor("/api/v2/wishlist/${wish.id}/dismiss",
                method = HttpMethod.POST, user = admin),
            wishId = wish.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshed = WishListItem.findById(wish.id!!)!!
        assertEquals(WishStatus.DISMISSED.name, refreshed.status)
    }

    // ---------------------- /api/v2/wishlist/transcode/{titleId} ----------------------

    @Test
    fun `addTranscodeWish persists a transcode wish for an existing title`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Backlog Title", media_type = MediaType.MOVIE.name,
            sort_name = "backlog title").apply { save() }

        val resp = service.addTranscodeWish(
            ctxFor("/api/v2/wishlist/transcode/${title.id}",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(true, body.get("ok").asBoolean)
    }

    @Test
    fun `addTranscodeWish returns ok=false on duplicate`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Already Wished", media_type = MediaType.MOVIE.name,
            sort_name = "already wished").apply { save() }

        service.addTranscodeWish(
            ctxFor("/api/v2/wishlist/transcode/${title.id}",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        val second = service.addTranscodeWish(
            ctxFor("/api/v2/wishlist/transcode/${title.id}",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        assertEquals(false, readJsonObject(second).get("ok").asBoolean)
    }

    @Test
    fun `removeTranscodeWish returns 404 when the wish belongs to another user`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val title = Title(name = "Title", media_type = MediaType.MOVIE.name,
            sort_name = "title").apply { save() }
        val wish = WishListItem(
            user_id = owner.id!!, wish_type = WishType.TRANSCODE.name,
            status = WishStatus.ACTIVE.name,
            title_id = title.id,
        ).apply { save() }

        val resp = service.removeTranscodeWish(
            ctxFor("/api/v2/wishlist/transcode/${wish.id}",
                method = HttpMethod.DELETE, user = other),
            wishId = wish.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `removeTranscodeWish deletes the row when owned by the calling user`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = Title(name = "Delete Me", media_type = MediaType.MOVIE.name,
            sort_name = "delete me").apply { save() }
        val wish = WishListItem(
            user_id = admin.id!!, wish_type = WishType.TRANSCODE.name,
            status = WishStatus.ACTIVE.name,
            title_id = title.id,
        ).apply { save() }

        val resp = service.removeTranscodeWish(
            ctxFor("/api/v2/wishlist/transcode/${wish.id}",
                method = HttpMethod.DELETE, user = admin),
            wishId = wish.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, WishListItem.findAll().size)
    }
}
