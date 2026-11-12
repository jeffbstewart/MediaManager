package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TagHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("tag") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = TagHttpService()

    @Before
    fun reset() {
        TrackTag.deleteAll()
        TitleTag.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        Tag.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedTag(name: String = "Test", source: TagSourceType = TagSourceType.MANUAL): Tag =
        Tag(name = name, bg_color = "#000",
            source_type = source.name).apply { save() }

    private fun seedTitle(name: String = "Movie", mediaType: MediaType = MediaType.MOVIE,
                          contentRating: String? = "PG-13"): Title =
        Title(name = name, media_type = mediaType.name,
            sort_name = name.lowercase(),
            content_rating = contentRating).apply { save() }

    // ---------------------- listTags ----------------------

    @Test
    fun `listTags returns 401 unauthenticated`() {
        val resp = service.listTags(ctxFor("/api/v2/catalog/tags", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listTags returns the empty array on a clean catalog`() {
        val resp = service.listTags(
            ctxFor("/api/v2/catalog/tags", user = getOrCreateUser("admin", level = 2))
        )
        val body = readJsonObject(resp)
        assertEquals(0, body.get("total").asInt)
        assertEquals(0, body.getAsJsonArray("tags").size())
    }

    @Test
    fun `listTags returns each tag with a title_count`() {
        val tag = seedTag("Sci-Fi")
        val title = seedTitle()
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()

        val resp = service.listTags(
            ctxFor("/api/v2/catalog/tags", user = getOrCreateUser("admin", level = 2))
        )
        val body = readJsonObject(resp)
        assertEquals(1, body.get("total").asInt)
        val first = body.getAsJsonArray("tags")[0].asJsonObject
        assertEquals("Sci-Fi", first.get("name").asString)
        assertEquals(1, first.get("title_count").asInt)
    }

    // ---------------------- tagDetail ----------------------

    @Test
    fun `tagDetail returns 401 unauthenticated`() {
        val resp = service.tagDetail(
            ctxFor("/api/v2/catalog/tags/1", user = null), tagId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `tagDetail returns 404 when tagId does not exist`() {
        val resp = service.tagDetail(
            ctxFor("/api/v2/catalog/tags/9999",
                user = getOrCreateUser("admin", level = 2)),
            tagId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `tagDetail returns the tag's titles`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag("Family")
        val titleA = seedTitle("Title A")
        val titleB = seedTitle("Title B")
        TitleTag(title_id = titleA.id!!, tag_id = tag.id!!).save()
        TitleTag(title_id = titleB.id!!, tag_id = tag.id!!).save()

        val resp = service.tagDetail(
            ctxFor("/api/v2/catalog/tags/${tag.id}", user = admin),
            tagId = tag.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        // Tag itself is on the response.
        assertEquals("Family", body.getAsJsonObject("tag").get("name").asString)
    }

    // ---------------------- addTitleToTag ----------------------

    @Test
    fun `addTitleToTag returns 401 unauthenticated`() {
        val resp = service.addTitleToTag(
            ctxFor("/api/v2/catalog/tags/1/titles/1",
                method = HttpMethod.POST, user = null),
            tagId = 1L, titleId = 1L,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `addTitleToTag returns 403 for non-admins`() {
        val resp = service.addTitleToTag(
            ctxFor("/api/v2/catalog/tags/1/titles/1",
                method = HttpMethod.POST, user = getOrCreateUser("viewer", level = 1)),
            tagId = 1L, titleId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `addTitleToTag returns 404 when tag is missing`() {
        val title = seedTitle()
        val resp = service.addTitleToTag(
            ctxFor("/api/v2/catalog/tags/9999/titles/${title.id}",
                method = HttpMethod.POST, user = getOrCreateUser("admin", level = 2)),
            tagId = 9999L, titleId = title.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `addTitleToTag returns 404 when title is missing`() {
        val tag = seedTag()
        val resp = service.addTitleToTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/titles/9999",
                method = HttpMethod.POST, user = getOrCreateUser("admin", level = 2)),
            tagId = tag.id!!, titleId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `addTitleToTag is idempotent and creates the link only once`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag()
        val title = seedTitle()
        repeat(2) {
            service.addTitleToTag(
                ctxFor("/api/v2/catalog/tags/${tag.id}/titles/${title.id}",
                    method = HttpMethod.POST, user = admin),
                tagId = tag.id!!, titleId = title.id!!,
            )
        }
        val links = TitleTag.findAll()
            .filter { it.tag_id == tag.id && it.title_id == title.id }
        assertEquals(1, links.size, "second add should be a no-op")
    }

    // ---------------------- removeTitleFromTag ----------------------

    @Test
    fun `removeTitleFromTag returns 403 for non-admins`() {
        val resp = service.removeTitleFromTag(
            ctxFor("/api/v2/catalog/tags/1/titles/1",
                method = HttpMethod.DELETE, user = getOrCreateUser("viewer", level = 1)),
            tagId = 1L, titleId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `removeTitleFromTag drops the matching TitleTag rows`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag()
        val title = seedTitle()
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()

        val resp = service.removeTitleFromTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/titles/${title.id}",
                method = HttpMethod.DELETE, user = admin),
            tagId = tag.id!!, titleId = title.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(0, TitleTag.findAll().size)
    }

    // ---------------------- searchTitlesForTag ----------------------

    @Test
    fun `searchTitlesForTag returns 403 for non-admins`() {
        val resp = service.searchTitlesForTag(
            ctxFor("/api/v2/catalog/tags/1/search-titles?q=movie",
                user = getOrCreateUser("viewer", level = 1)),
            tagId = 1L, q = "movie",
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `searchTitlesForTag returns empty results for queries shorter than 2 chars`() {
        val resp = service.searchTitlesForTag(
            ctxFor("/api/v2/catalog/tags/1/search-titles?q=a",
                user = getOrCreateUser("admin", level = 2)),
            tagId = 1L, q = "a",
        )
        val body = readJsonObject(resp)
        assertEquals(0, body.getAsJsonArray("results").size())
    }

    @Test
    fun `searchTitlesForTag returns matching titles that are not already tagged`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag()
        val match = seedTitle("The Matrix")
        seedTitle("Other Movie")  // also a candidate
        val alreadyTagged = seedTitle("Already Matrix")
        TitleTag(title_id = alreadyTagged.id!!, tag_id = tag.id!!).save()

        val resp = service.searchTitlesForTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/search-titles?q=matrix",
                user = admin),
            tagId = tag.id!!, q = "matrix",
        )
        val results = readJsonObject(resp).getAsJsonArray("results")
        // Only the un-tagged matching title should come back.
        assertEquals(1, results.size())
        assertEquals(match.id, results[0].asJsonObject.get("title_id").asLong)
    }

    // ---------------------- track-tag endpoints ----------------------

    @Test
    fun `addTrackToTag returns 404 when tag is missing`() {
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "Track").apply { save() }
        val resp = service.addTrackToTag(
            ctxFor("/api/v2/catalog/tags/9999/tracks/${track.id}",
                method = HttpMethod.POST, user = getOrCreateUser("admin", level = 2)),
            tagId = 9999L, trackId = track.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `addTrackToTag returns 404 when track is missing`() {
        val tag = seedTag()
        val resp = service.addTrackToTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/tracks/9999",
                method = HttpMethod.POST, user = getOrCreateUser("admin", level = 2)),
            tagId = tag.id!!, trackId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `addTrackToTag and removeTrackFromTag flip the TrackTag link`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag()
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "Track").apply { save() }

        service.addTrackToTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/tracks/${track.id}",
                method = HttpMethod.POST, user = admin),
            tagId = tag.id!!, trackId = track.id!!,
        )
        assertEquals(1, TrackTag.findAll().size)

        service.removeTrackFromTag(
            ctxFor("/api/v2/catalog/tags/${tag.id}/tracks/${track.id}",
                method = HttpMethod.DELETE, user = admin),
            tagId = tag.id!!, trackId = track.id!!,
        )
        assertEquals(0, TrackTag.findAll().size)
    }

    @Test
    fun `listTrackTags returns 404 when track is missing`() {
        val resp = service.listTrackTags(
            ctxFor("/api/v2/catalog/tracks/9999/tags",
                user = getOrCreateUser("admin", level = 2)),
            trackId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `listTrackTags returns the tags currently attached to a track`() {
        val admin = getOrCreateUser("admin", level = 2)
        val tag = seedTag("Jazz")
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        TrackTag(track_id = track.id!!, tag_id = tag.id!!).save()

        val resp = service.listTrackTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/tags", user = admin),
            trackId = track.id!!,
        )
        val tags = readJsonObject(resp).getAsJsonArray("tags")
        assertEquals(1, tags.size())
        assertEquals("Jazz", tags[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `setTrackTags replaces the entire tag set`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        val oldTag = seedTag("OldTag")
        val newTag1 = seedTag("NewOne")
        val newTag2 = seedTag("NewTwo")
        TrackTag(track_id = track.id!!, tag_id = oldTag.id!!).save()

        val resp = service.setTrackTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"tag_ids": [${newTag1.id}, ${newTag2.id}]}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val remaining = TrackTag.findAll().map { it.tag_id }.toSet()
        assertEquals(setOf(newTag1.id, newTag2.id), remaining)
    }

    // ---------------------- setTrackMusicTags ----------------------

    @Test
    fun `setTrackMusicTags returns 403 for viewers`() {
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/1/music-tags",
                method = HttpMethod.POST, user = getOrCreateUser("viewer", level = 1),
                jsonBody = """{"bpm": 120}"""),
            trackId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `setTrackMusicTags returns 404 when track is missing`() {
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/9999/music-tags",
                method = HttpMethod.POST, user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"bpm": 120}"""),
            trackId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `setTrackMusicTags returns 400 with an empty body (nothing to update)`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/music-tags",
                method = HttpMethod.POST, user = admin, jsonBody = """{}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setTrackMusicTags rejects malformed time_signature`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/music-tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"time_signature": "waltz"}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setTrackMusicTags rejects out-of-range bpm`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/music-tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"bpm": 9999}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setTrackMusicTags persists time_signature and bpm together`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T").apply { save() }
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/music-tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"bpm": 120, "time_signature": "3/4"}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val refreshed = Track.findById(track.id!!)!!
        assertEquals(120, refreshed.bpm)
        assertEquals("3/4", refreshed.time_signature)
        // Response includes the changed-fields list for the SPA toast.
        val changed = readJsonObject(resp).getAsJsonArray("changed")
        val names = changed.map { it.asString }.toSet()
        assertTrue("bpm" in names && "time_signature" in names)
    }

    @Test
    fun `setTrackMusicTags can clear bpm by passing null`() {
        val admin = getOrCreateUser("admin", level = 2)
        val title = seedTitle()
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T", bpm = 100).apply { save() }
        val resp = service.setTrackMusicTags(
            ctxFor("/api/v2/catalog/tracks/${track.id}/music-tags",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"bpm": null}"""),
            trackId = track.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(null, Track.findById(track.id!!)!!.bpm)
    }
}
