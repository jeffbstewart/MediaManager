package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class TagServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:tagtest;DB_CLOSE_DELAY=-1"
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

    @Before
    fun cleanup() {
        TrackTag.deleteAll()
        TitleTag.deleteAll()
        Track.deleteAll()
        Tag.deleteAll()
        Title.deleteAll()
    }

    @After
    fun cleanupAfter() {
        TrackTag.deleteAll()
        TitleTag.deleteAll()
        Track.deleteAll()
        Tag.deleteAll()
        Title.deleteAll()
    }

    private fun createTitle(name: String): Title {
        val t = Title(name = name, sort_name = name, enrichment_status = "PENDING")
        t.save()
        return t
    }

    @Test
    fun `createTag persists and returns tag`() {
        val tag = TagService.createTag("Comfort Movies", "#EF4444")
        assertNotNull(tag.id)
        assertEquals("Comfort Movies", tag.name)
        assertEquals("#EF4444", tag.bg_color)
    }

    @Test
    fun `updateTag changes name and color`() {
        val tag = TagService.createTag("Old Name", "#EF4444")
        val updated = TagService.updateTag(tag.id!!, "New Name", "#3B82F6")
        assertNotNull(updated)
        assertEquals("New Name", updated.name)
        assertEquals("#3B82F6", updated.bg_color)
    }

    @Test
    fun `deleteTag removes tag and cascades title_tag`() {
        val tag = TagService.createTag("Temp", "#EF4444")
        val title = createTitle("Movie A")
        TagService.addTagToTitle(title.id!!, tag.id!!)

        assertEquals(1, TitleTag.count())
        TagService.deleteTag(tag.id!!)
        assertNull(Tag.findById(tag.id!!))
        assertEquals(0, TitleTag.count())
    }

    @Test
    fun `addTagToTitle is idempotent`() {
        val tag = TagService.createTag("Action", "#EF4444")
        val title = createTitle("Movie B")

        TagService.addTagToTitle(title.id!!, tag.id!!)
        TagService.addTagToTitle(title.id!!, tag.id!!)

        assertEquals(1, TitleTag.count())
    }

    @Test
    fun `getTagsForTitle returns tags for a title`() {
        val tag1 = TagService.createTag("Comedy", "#EF4444")
        val tag2 = TagService.createTag("Drama", "#3B82F6")
        val title = createTitle("Movie C")

        TagService.addTagToTitle(title.id!!, tag1.id!!)
        TagService.addTagToTitle(title.id!!, tag2.id!!)

        val tags = TagService.getTagsForTitle(title.id!!)
        assertEquals(2, tags.size)
        assertTrue(tags.any { it.name == "Comedy" })
        assertTrue(tags.any { it.name == "Drama" })
    }

    @Test
    fun `removeTagFromTitle removes association`() {
        val tag = TagService.createTag("Horror", "#EF4444")
        val title = createTitle("Movie D")

        TagService.addTagToTitle(title.id!!, tag.id!!)
        assertEquals(1, TagService.getTagsForTitle(title.id!!).size)

        TagService.removeTagFromTitle(title.id!!, tag.id!!)
        assertEquals(0, TagService.getTagsForTitle(title.id!!).size)
    }

    @Test
    fun `getTitleIdsForTags returns OR union`() {
        val tag1 = TagService.createTag("A", "#EF4444")
        val tag2 = TagService.createTag("B", "#3B82F6")
        val title1 = createTitle("Movie 1")
        val title2 = createTitle("Movie 2")
        val title3 = createTitle("Movie 3")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)
        TagService.addTagToTitle(title2.id!!, tag2.id!!)
        TagService.addTagToTitle(title3.id!!, tag1.id!!)
        TagService.addTagToTitle(title3.id!!, tag2.id!!)

        val result = TagService.getTitleIdsForTags(setOf(tag1.id!!, tag2.id!!))
        assertEquals(setOf(title1.id, title2.id, title3.id), result)
    }

    @Test
    fun `getTitleIdsForTags with single tag`() {
        val tag1 = TagService.createTag("C", "#EF4444")
        val title1 = createTitle("Movie X")
        val title2 = createTitle("Movie Y")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)

        val result = TagService.getTitleIdsForTags(setOf(tag1.id!!))
        assertEquals(setOf(title1.id), result)
    }

    @Test
    fun `getTagTitleCounts returns correct counts`() {
        val tag1 = TagService.createTag("D", "#EF4444")
        val tag2 = TagService.createTag("E", "#3B82F6")
        val title1 = createTitle("Movie P")
        val title2 = createTitle("Movie Q")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)
        TagService.addTagToTitle(title2.id!!, tag1.id!!)
        TagService.addTagToTitle(title1.id!!, tag2.id!!)

        val counts = TagService.getTagTitleCounts()
        assertEquals(2, counts[tag1.id])
        assertEquals(1, counts[tag2.id])
    }

    @Test
    fun `isNameUnique validates uniqueness`() {
        val tag = TagService.createTag("Unique", "#EF4444")

        assertFalse(TagService.isNameUnique("Unique"))
        assertFalse(TagService.isNameUnique("unique")) // case insensitive
        assertTrue(TagService.isNameUnique("Different"))
        assertTrue(TagService.isNameUnique("Unique", excludeTagId = tag.id)) // self-exclude
    }

    // ==============================================================
    // Tags phase B — track-level tags
    // ==============================================================

    private fun createTrack(titleId: Long, num: Int, name: String): Track {
        val t = Track(
            title_id = titleId,
            track_number = num,
            disc_number = 1,
            name = name,
            file_path = "/fake/${titleId}_${num}.flac"
        )
        t.save()
        return t
    }

    private fun createAlbum(name: String): Title {
        val t = Title(
            name = name,
            sort_name = name,
            media_type = MediaType.ALBUM.name,
            enrichment_status = "ENRICHED"
        )
        t.save()
        return t
    }

    @Test
    fun `addTagToTrack is idempotent and survives repeat calls`() {
        val album = createAlbum("A")
        val track = createTrack(album.id!!, 1, "T")
        val tag = TagService.createTag("Workout", "#22C55E")

        TagService.addTagToTrack(track.id!!, tag.id!!)
        TagService.addTagToTrack(track.id!!, tag.id!!)
        TagService.addTagToTrack(track.id!!, tag.id!!)

        val attached = TagService.getTagsForTrack(track.id!!)
        assertEquals(1, attached.size)
        assertEquals(tag.id, attached[0].id)
    }

    @Test
    fun `addTagToTrack silently no-ops for unknown track or tag`() {
        val album = createAlbum("A")
        val track = createTrack(album.id!!, 1, "T")
        val tag = TagService.createTag("Workout", "#22C55E")

        TagService.addTagToTrack(99999L, tag.id!!)
        TagService.addTagToTrack(track.id!!, 99999L)

        assertEquals(0, TrackTag.findAll().size)
    }

    @Test
    fun `removeTagFromTrack drops the link`() {
        val album = createAlbum("A")
        val track = createTrack(album.id!!, 1, "T")
        val tag = TagService.createTag("Workout", "#22C55E")

        TagService.addTagToTrack(track.id!!, tag.id!!)
        assertEquals(1, TagService.getTagsForTrack(track.id!!).size)

        TagService.removeTagFromTrack(track.id!!, tag.id!!)
        assertEquals(0, TagService.getTagsForTrack(track.id!!).size)
    }

    @Test
    fun `getTrackIdsForTags returns OR-union across tag ids`() {
        val album = createAlbum("A")
        val t1 = createTrack(album.id!!, 1, "one")
        val t2 = createTrack(album.id!!, 2, "two")
        val t3 = createTrack(album.id!!, 3, "three")
        val workout = TagService.createTag("Workout", "#22C55E")
        val chill = TagService.createTag("Chill", "#3B82F6")

        TagService.addTagToTrack(t1.id!!, workout.id!!)
        TagService.addTagToTrack(t2.id!!, chill.id!!)
        TagService.addTagToTrack(t3.id!!, workout.id!!)
        TagService.addTagToTrack(t3.id!!, chill.id!!)

        assertEquals(
            setOf(t1.id!!, t3.id!!),
            TagService.getTrackIdsForTags(setOf(workout.id!!))
        )
        assertEquals(
            setOf(t1.id!!, t2.id!!, t3.id!!),
            TagService.getTrackIdsForTags(setOf(workout.id!!, chill.id!!))
        )
    }

    @Test
    fun `getTagTrackCounts returns per-tag counts`() {
        val album = createAlbum("A")
        val t1 = createTrack(album.id!!, 1, "one")
        val t2 = createTrack(album.id!!, 2, "two")
        val tag = TagService.createTag("Workout", "#22C55E")

        TagService.addTagToTrack(t1.id!!, tag.id!!)
        TagService.addTagToTrack(t2.id!!, tag.id!!)

        assertEquals(2, TagService.getTagTrackCounts()[tag.id!!])
    }

    @Test
    fun `deleteTag also removes track_tag rows`() {
        val album = createAlbum("A")
        val track = createTrack(album.id!!, 1, "T")
        val tag = TagService.createTag("Workout", "#22C55E")
        TagService.addTagToTrack(track.id!!, tag.id!!)

        TagService.deleteTag(tag.id!!)

        assertEquals(0, TrackTag.findAll().size)
        assertNull(Tag.findById(tag.id!!))
    }

    @Test
    fun `same tag can mark a title and a track independently`() {
        val album = createAlbum("A")
        val track = createTrack(album.id!!, 1, "T")
        val tag = TagService.createTag("Favorites", "#EAB308")

        TagService.addTagToTitle(album.id!!, tag.id!!)
        TagService.addTagToTrack(track.id!!, tag.id!!)

        assertEquals(setOf(album.id!!), TagService.getTitleIdsForTags(setOf(tag.id!!)))
        assertEquals(setOf(track.id!!), TagService.getTrackIdsForTags(setOf(tag.id!!)))

        // Removing from the track leaves the title link alone.
        TagService.removeTagFromTrack(track.id!!, tag.id!!)
        assertEquals(setOf(album.id!!), TagService.getTitleIdsForTags(setOf(tag.id!!)))
        assertEquals(emptySet<Long>(), TagService.getTrackIdsForTags(setOf(tag.id!!)))
    }
}
