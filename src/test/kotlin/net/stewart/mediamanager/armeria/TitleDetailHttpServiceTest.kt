package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class TitleDetailHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("titledetail") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TitleDetailHttpService()

    @Before
    fun reset() {
        // FK-safe order: leaves first
        PlaybackProgress.deleteAll()
        UserTitleFlag.deleteAll()
        TitleTag.deleteAll()
        TitleGenre.deleteAll()
        TitleArtist.deleteAll()
        TitleAuthor.deleteAll()
        TitleSeason.deleteAll()
        CastMember.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Transcode.deleteAll()
        Episode.deleteAll()
        Track.deleteAll()
        Tag.deleteAll()
        Genre.deleteAll()
        Artist.deleteAll()
        Author.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedTitle(
        name: String = "Test Movie",
        mediaType: MediaType = MediaType.MOVIE,
        contentRating: String? = "PG-13",
    ): Title = Title(
        name = name,
        media_type = mediaType.name,
        sort_name = name.lowercase(),
        content_rating = contentRating,
        release_year = 2024,
    ).apply { save() }

    // ---------------------- titleDetail GET ----------------------

    @Test
    fun `titleDetail returns 401 unauthenticated`() {
        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/1", user = null),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `titleDetail returns 404 when titleId is unknown`() {
        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/9999",
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `titleDetail returns 403 when content rating exceeds viewer's ceiling`() {
        val viewer = getOrCreateUser("viewer", level = 1).apply {
            rating_ceiling = 4  // PG-13 ceiling
            save()
        }
        val title = seedTitle("Hard R", contentRating = "R")
        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = viewer),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `titleDetail returns the full shape for a basic movie title with no related rows`() {
        val title = seedTitle()
        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}",
                user = getOrCreateUser("admin", level = 2)),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        // Identity fields.
        assertEquals(title.id, body.get("title_id").asLong)
        assertEquals("Test Movie", body.get("title_name").asString)
        assertEquals("MOVIE", body.get("media_type").asString)
        // Per-user flags default to false.
        assertEquals(false, body.get("is_starred").asBoolean)
        assertEquals(false, body.get("is_hidden").asBoolean)
        // Empty collections present (Angular shell expects them).
        for (k in listOf("genres", "tags", "formats", "transcodes",
            "cast", "episodes", "seasons", "family_members", "similar_titles",
            "authors", "tracks", "artists", "personnel", "readable_editions")) {
            assertTrue(body.has(k), "missing key '$k'")
            assertTrue(body.get(k).isJsonArray, "'$k' should be an array")
        }
    }

    @Test
    fun `titleDetail surfaces genres, tags, and per-user flags`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val genre = Genre(name = "Sci-Fi").apply { save() }
        TitleGenre(title_id = title.id!!, genre_id = genre.id!!).save()
        val tag = Tag(name = "Mind-Bending", bg_color = "#222").apply { save() }
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()
        UserTitleFlag(user_id = admin.id!!, title_id = title.id!!,
            flag = UserFlagType.STARRED.name).save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        assertEquals(true, body.get("is_starred").asBoolean)
        val genres = body.getAsJsonArray("genres")
        assertEquals(1, genres.size())
        assertEquals("Sci-Fi", genres[0].asString)
        val tags = body.getAsJsonArray("tags")
        assertEquals(1, tags.size())
        assertEquals("Mind-Bending", tags[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `titleDetail includes admin_media_items only for admins`() {
        val admin = getOrCreateUser("admin", level = 2)
        val viewer = getOrCreateUser("viewer", level = 1)
        val title = seedTitle()
        val item = MediaItem(media_format = MediaFormat.BLURAY.name, upc = "123").apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()

        val adminResp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val adminBody = readJsonObject(adminResp)
        assertEquals(1, adminBody.getAsJsonArray("admin_media_items").size())

        val viewerResp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = viewer),
            titleId = title.id!!,
        )
        val viewerBody = readJsonObject(viewerResp)
        assertEquals(0, viewerBody.getAsJsonArray("admin_media_items").size())
        // Public formats array still surfaces the format string.
        assertEquals(1, viewerBody.getAsJsonArray("formats").size())
        assertEquals("BLURAY", viewerBody.getAsJsonArray("formats")[0].asString)
    }

    @Test
    fun `titleDetail surfaces cast and similar titles for non-personal titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle("Main")
        CastMember(title_id = title.id!!, name = "Star Actor",
            character_name = "Hero",
            tmdb_person_id = 100, profile_path = "/p.jpg",
            cast_order = 0).save()
        // SimilarTitlesService returns titles sharing genre/tags. Seed a
        // second title sharing a tag to give it a candidate.
        val similar = seedTitle("Similar")
        val tag = Tag(name = "shared", bg_color = "#fff").apply { save() }
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()
        TitleTag(title_id = similar.id!!, tag_id = tag.id!!).save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        val cast = body.getAsJsonArray("cast")
        assertEquals(1, cast.size())
        assertEquals("Star Actor", cast[0].asJsonObject.get("name").asString)
        assertEquals("Hero", cast[0].asJsonObject.get("character_name").asString)
    }

    @Test
    fun `titleDetail surfaces episodes and seasons for TV titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle("Show", mediaType = MediaType.TV)
        Episode(title_id = title.id!!, season_number = 1, episode_number = 1,
            name = "Pilot").save()
        Episode(title_id = title.id!!, season_number = 1, episode_number = 2,
            name = "Second Episode").save()
        TitleSeason(title_id = title.id!!, season_number = 1,
            acquisition_status = "OWNED").save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        assertEquals(2, body.getAsJsonArray("episodes").size())
        assertEquals(1, body.getAsJsonArray("seasons").size())
        assertEquals(1, body.getAsJsonArray("seasons")[0].asJsonObject
            .get("season_number").asInt)
    }

    @Test
    fun `titleDetail surfaces authors for book titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle("Book Title", mediaType = MediaType.BOOK)
        val author = Author(name = "Author Name", sort_name = "author name").apply { save() }
        TitleAuthor(title_id = title.id!!, author_id = author.id!!,
            author_order = 0).save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        val authors = body.getAsJsonArray("authors")
        assertEquals(1, authors.size())
        assertEquals("Author Name", authors[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `titleDetail surfaces artists and tracks for album titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle("Album Title", mediaType = MediaType.ALBUM)
        val artist = Artist(name = "Band", sort_name = "band",
            artist_type = ArtistType.GROUP.name).apply { save() }
        TitleArtist(title_id = title.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "Track One").save()
        Track(title_id = title.id!!, track_number = 2, disc_number = 1,
            name = "Track Two").save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        assertEquals(1, body.getAsJsonArray("artists").size())
        assertEquals(2, body.getAsJsonArray("tracks").size())
        // Tracks ordered by (disc, track) — first should be track_number=1.
        assertEquals(1, body.getAsJsonArray("tracks")[0].asJsonObject
            .get("track_number").asInt)
    }

    @Test
    fun `titleDetail strips cast, genres, similar for personal titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle("Vacation", mediaType = MediaType.PERSONAL)
        // These should be ignored when media_type = PERSONAL.
        CastMember(title_id = title.id!!, name = "Should not appear",
            tmdb_person_id = 1, cast_order = 0).save()
        val genre = Genre(name = "Drama").apply { save() }
        TitleGenre(title_id = title.id!!, genre_id = genre.id!!).save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val body = readJsonObject(resp)
        assertEquals(0, body.getAsJsonArray("cast").size())
        assertEquals(0, body.getAsJsonArray("genres").size())
        assertEquals(0, body.getAsJsonArray("similar_titles").size())
    }

    @Test
    fun `titleDetail surfaces transcodes with playable flag`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        // mp4 source — needsTranscoding is false → playable = true regardless of nasRoot.
        Transcode(title_id = title.id!!,
            file_path = "/nas/movies/Movie.mp4").save()
        // mkv source — needsTranscoding is true; without nasRoot configured,
        // isTranscoded short-circuits to false and playable = false.
        Transcode(title_id = title.id!!,
            file_path = "/nas/movies/Movie.mkv").save()

        val resp = service.titleDetail(
            ctxFor("/api/v2/catalog/titles/${title.id}", user = admin),
            titleId = title.id!!,
        )
        val transcodes = readJsonObject(resp).getAsJsonArray("transcodes")
        assertEquals(2, transcodes.size())
        val playabilityByName = transcodes.associate {
            it.asJsonObject.get("file_name").asString to it.asJsonObject.get("playable").asBoolean
        }
        assertEquals(true, playabilityByName["Movie.mp4"])
        assertEquals(false, playabilityByName["Movie.mkv"])
    }

    // ---------------------- toggleStar POST ----------------------

    @Test
    fun `toggleStar returns 401 unauthenticated`() {
        val resp = service.toggleStar(
            ctxFor("/api/v2/catalog/titles/1/star", method = HttpMethod.POST,
                user = null),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `toggleStar returns 404 when title is missing`() {
        val resp = service.toggleStar(
            ctxFor("/api/v2/catalog/titles/9999/star", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `toggleStar flips the flag on each call`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()

        val first = service.toggleStar(
            ctxFor("/api/v2/catalog/titles/${title.id}/star",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        assertEquals(true, readJsonObject(first).get("is_starred").asBoolean)

        val second = service.toggleStar(
            ctxFor("/api/v2/catalog/titles/${title.id}/star",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        assertEquals(false, readJsonObject(second).get("is_starred").asBoolean)
    }

    // ---------------------- toggleHide POST ----------------------

    @Test
    fun `toggleHide returns 401 unauthenticated`() {
        val resp = service.toggleHide(
            ctxFor("/api/v2/catalog/titles/1/hide", method = HttpMethod.POST,
                user = null),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `toggleHide returns 404 on missing title`() {
        val resp = service.toggleHide(
            ctxFor("/api/v2/catalog/titles/9999/hide", method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2)),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `toggleHide sets the flag on first call`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val resp = service.toggleHide(
            ctxFor("/api/v2/catalog/titles/${title.id}/hide",
                method = HttpMethod.POST, user = admin),
            titleId = title.id!!,
        )
        assertEquals(true, readJsonObject(resp).get("is_hidden").asBoolean)
    }

    // ---------------------- setTags POST ----------------------

    @Test
    fun `setTags returns 401 unauthenticated`() {
        val resp = service.setTags(
            ctxFor("/api/v2/catalog/titles/1/tags",
                method = HttpMethod.POST, user = null,
                jsonBody = """{"tag_ids": []}"""),
            titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `setTags returns 403 for non-admin viewers`() {
        val viewer = getOrCreateUser("viewer", level = 1)
        val title = seedTitle()
        val resp = service.setTags(
            ctxFor("/api/v2/catalog/titles/${title.id}/tags",
                method = HttpMethod.POST, user = viewer,
                jsonBody = """{"tag_ids": []}"""),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `setTags returns 404 when title is missing`() {
        val resp = service.setTags(
            ctxFor("/api/v2/catalog/titles/9999/tags",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"tag_ids": []}"""),
            titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `setTags replaces the existing tag set with the provided ids`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val oldTag = Tag(name = "Old", bg_color = "#000").apply { save() }
        val newTag1 = Tag(name = "New One", bg_color = "#111").apply { save() }
        val newTag2 = Tag(name = "New Two", bg_color = "#222").apply { save() }
        TitleTag(title_id = title.id!!, tag_id = oldTag.id!!).save()

        val resp = service.setTags(
            ctxFor("/api/v2/catalog/titles/${title.id}/tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"tag_ids": [${newTag1.id}, ${newTag2.id}]}"""),
            titleId = title.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val remaining = TitleTag.findAll().filter { it.title_id == title.id!! }
            .map { it.tag_id }.toSet()
        assertEquals(setOf(newTag1.id, newTag2.id), remaining,
            "old tag wiped, new tags attached")
    }
}
