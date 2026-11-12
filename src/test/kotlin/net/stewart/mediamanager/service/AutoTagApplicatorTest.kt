package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [AutoTagApplicator] — pure helpers and the track/album
 * application flow against an in-memory H2 database.
 */
class AutoTagApplicatorTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:autotagtest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardown() {
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

    // ----------------------------- unit -------------------------

    @Test
    fun `canonicalize lowercases, trims, and collapses whitespace`() {
        assertEquals("post-punk", AutoTagApplicator.canonicalize("  Post-Punk  "))
        assertEquals("indie rock", AutoTagApplicator.canonicalize("Indie   Rock"))
    }

    @Test
    fun `canonicalize drops meaningless values`() {
        assertNull(AutoTagApplicator.canonicalize(""))
        assertNull(AutoTagApplicator.canonicalize("  "))
        assertNull(AutoTagApplicator.canonicalize("Unknown"))
        assertNull(AutoTagApplicator.canonicalize("N/A"))
    }

    @Test
    fun `canonicalize applies synonyms`() {
        // Obvious drift to collapse — "Rock and Roll" → "rock".
        assertEquals("rock", AutoTagApplicator.canonicalize("Rock and Roll"))
        assertEquals("hip-hop", AutoTagApplicator.canonicalize("Hip Hop"))
    }

    @Test
    fun `bucketForBpm groups into 20-bpm windows`() {
        assertEquals("<60", AutoTagApplicator.bucketForBpm(45))
        assertEquals("60-80", AutoTagApplicator.bucketForBpm(60))
        assertEquals("80-100", AutoTagApplicator.bucketForBpm(99))
        assertEquals("120-140", AutoTagApplicator.bucketForBpm(128))
        assertEquals("180+", AutoTagApplicator.bucketForBpm(200))
        assertNull(AutoTagApplicator.bucketForBpm(null))
        assertNull(AutoTagApplicator.bucketForBpm(0))
    }

    @Test
    fun `decadeFor rolls to decade boundary`() {
        assertEquals("1980s", AutoTagApplicator.decadeFor(1984))
        assertEquals("2010s", AutoTagApplicator.decadeFor(2019))
        assertNull(AutoTagApplicator.decadeFor(null))
        assertNull(AutoTagApplicator.decadeFor(1500))
    }

    // ----------------------------- integration -----------------

    @Test
    fun `applyToTrack creates tags once and attaches them`() {
        val album = seedAlbum("A", 1984)
        val track = seedTrack(album.id!!, 1, "T")

        AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
            trackId = track.id!!,
            genres = listOf("Rock", "Pop-Rock"),
            styles = listOf("Post-Punk"),
            bpm = 128,
            timeSignature = "4/4",
            year = 1984
        ))

        val tagsOnTrack = Tag.findAll()
            .filter { it.id in TrackTag.findAll().filter { tt -> tt.track_id == track.id }.map { tt -> tt.tag_id }.toSet() }
            .map { it.source_type to it.source_key }
            .toSet()

        assertTrue(TagSourceType.GENRE.name to "rock" in tagsOnTrack)
        assertTrue(TagSourceType.GENRE.name to "pop-rock" in tagsOnTrack)
        assertTrue(TagSourceType.STYLE.name to "post-punk" in tagsOnTrack)
        assertTrue(TagSourceType.BPM_BUCKET.name to "120-140" in tagsOnTrack)
        assertTrue(TagSourceType.TIME_SIG.name to "4/4" in tagsOnTrack)
        assertTrue(TagSourceType.DECADE.name to "1980s" in tagsOnTrack)
    }

    @Test
    fun `applyToTrack collapses genre+style with same canonical name into one TrackTag row`() {
        // Regression: GENRE "Pop" and STYLE "Pop" resolve to the same
        // Tag row (via the display-name fallback in findOrCreateTag).
        // Inserting both caused a UNIQUE constraint hit on
        // idx_track_tag_dedup. applyToTrack must dedup by tag id.
        val album = seedAlbum("A", 1984)
        val track = seedTrack(album.id!!, 1, "T")

        AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
            trackId = track.id!!,
            genres = listOf("Pop"),
            styles = listOf("Pop"),
            bpm = null, timeSignature = null, year = null
        ))

        val rows = TrackTag.findAll().filter { it.track_id == track.id }
        assertEquals(1, rows.size, "genre+style with same name must produce one TrackTag row")
    }

    @Test
    fun `applyToTrack is idempotent`() {
        val album = seedAlbum("A", 1984)
        val track = seedTrack(album.id!!, 1, "T")

        val input = AutoTagApplicator.TrackAutoTagInput(
            trackId = track.id!!,
            genres = listOf("Rock"),
            styles = emptyList(),
            bpm = 120,
            timeSignature = null,
            year = 1984
        )
        AutoTagApplicator.applyToTrack(input)
        AutoTagApplicator.applyToTrack(input)

        // Only one row per tag attached.
        assertEquals(
            TrackTag.findAll().filter { it.track_id == track.id }.size,
            TrackTag.findAll().filter { it.track_id == track.id }.map { it.tag_id }.distinct().size
        )
    }

    @Test
    fun `applyToAlbum promotes a majority genre to the title`() {
        val album = seedAlbum("A", 1984)
        val t1 = seedTrack(album.id!!, 1, "t1")
        val t2 = seedTrack(album.id!!, 2, "t2")
        val t3 = seedTrack(album.id!!, 3, "t3")
        val t4 = seedTrack(album.id!!, 4, "t4")

        // 3 of 4 tracks carry "rock" → should promote. Only 1 carries
        // "jazz" → should NOT promote.
        for (track in listOf(t1, t2, t3)) {
            AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
                trackId = track.id!!, genres = listOf("Rock"), styles = emptyList(),
                bpm = null, timeSignature = null, year = 1984
            ))
        }
        AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
            trackId = t4.id!!, genres = listOf("Jazz"), styles = emptyList(),
            bpm = null, timeSignature = null, year = 1984
        ))

        AutoTagApplicator.applyToAlbum(album.id!!)

        val titleTags = Tag.findAll().filter { it.id in
            TitleTag.findAll().filter { tt -> tt.title_id == album.id }.map { tt -> tt.tag_id }.toSet() }
        val keys = titleTags.map { it.source_type to it.source_key }.toSet()

        assertTrue(TagSourceType.GENRE.name to "rock" in keys)
        assertTrue(TagSourceType.DECADE.name to "1980s" in keys, "decade propagates even without a genre vote")
        assertTrue(TagSourceType.GENRE.name to "jazz" !in keys,
            "minority genre must not promote to album-level")
    }

    @Test
    fun `applyToAlbum does not promote BPM bucket or time signature`() {
        val album = seedAlbum("A", 1984)
        val t1 = seedTrack(album.id!!, 1, "t1")
        val t2 = seedTrack(album.id!!, 2, "t2")

        // Both tracks on an album in the same BPM bucket with the same
        // time signature — we STILL don't propagate those to the album.
        for (track in listOf(t1, t2)) {
            AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
                trackId = track.id!!, genres = emptyList(), styles = emptyList(),
                bpm = 128, timeSignature = "4/4", year = 1984
            ))
        }
        AutoTagApplicator.applyToAlbum(album.id!!)

        val titleTags = Tag.findAll().filter { it.id in
            TitleTag.findAll().filter { tt -> tt.title_id == album.id }.map { tt -> tt.tag_id }.toSet() }
        val sources = titleTags.map { it.source_type }.toSet()

        assertTrue(TagSourceType.BPM_BUCKET.name !in sources)
        assertTrue(TagSourceType.TIME_SIG.name !in sources)
    }

    // ----------------------------- fixture -----------------

    private fun seedAlbum(name: String, year: Int): Title {
        val t = Title(
            name = name, sort_name = name, media_type = MediaType.ALBUM.name,
            release_year = year,
            enrichment_status = EnrichmentStatus.ENRICHED.name
        )
        t.save()
        return t
    }

    private fun seedTrack(titleId: Long, num: Int, name: String): Track {
        val t = Track(
            title_id = titleId, track_number = num, disc_number = 1,
            name = name, file_path = "/fake/$titleId-$num.flac",
            created_at = LocalDateTime.now(), updated_at = LocalDateTime.now()
        )
        t.save()
        return t
    }
}
