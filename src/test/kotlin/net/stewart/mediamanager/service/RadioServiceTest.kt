package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.Track
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the Start Radio engine against a controlled in-memory library.
 *
 * Fixture shape:
 *
 * - Seed artist: **The Police** (not owned — we want unique seed → similar
 *   intersection logic). Two owned "similar" artists (**The Clash**, **The
 *   Cure**) plus one unrelated (**Abba**) so we can assert the intersection
 *   works. Each owned artist has a single album with three playable tracks.
 * - A Last.fm stub returns a scripted similar-artist list for The Police's
 *   MBID; for other MBIDs it returns empty so the batch doesn't branch
 *   into recursive similarity.
 *
 * See docs/MUSIC.md §M7.
 */
class RadioServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:radiotest;DB_CLOSE_DELAY=-1"
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

        private val POLICE_MBID = "11111111-1111-1111-1111-111111111111"
        private val CLASH_MBID  = "22222222-2222-2222-2222-222222222222"
        private val CURE_MBID   = "33333333-3333-3333-3333-333333333333"
        private val ABBA_MBID   = "44444444-4444-4444-4444-444444444444"
    }

    /** Last.fm stub that returns a fixed similar-artist list keyed by seed MBID. */
    private class StubLastFm(private val responses: Map<String, List<LastFmSimilarArtist>>) : LastFmService {
        override fun fetchSimilarArtists(artistMbid: String, limit: Int): LastFmResult {
            val similar = responses[artistMbid] ?: return LastFmResult.NotFound
            val rawJson = buildString {
                append("{\"similarartists\":{\"artist\":[")
                append(similar.joinToString(",") {
                    """{"name":"${it.name}","mbid":"${it.musicBrainzArtistId ?: ""}","match":"${it.match}"}"""
                })
                append("]}}")
            }
            return LastFmResult.Success(similar, rawJson)
        }
    }

    @Before
    fun seedFixture() {
        // FK-safe cleanup — media_item_title has no ON DELETE CASCADE from
        // title, so we purge it first. Track / title_artist / title_genre
        // do cascade but we drop them explicitly so H2's order-sensitive
        // constraint checker stays happy across JUnit runs.
        net.stewart.mediamanager.entity.MediaItemTitle.deleteAll()
        TitleArtist.deleteAll()
        TitleGenre.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        Genre.deleteAll()
        val now = LocalDateTime.now()

        SimilarArtistService.lastFm = StubLastFm(mapOf(
            POLICE_MBID to listOf(
                LastFmSimilarArtist("The Clash",  CLASH_MBID,  0.87),
                LastFmSimilarArtist("The Cure",   CURE_MBID,   0.72),
                LastFmSimilarArtist("Blondie",    "55555555-5555-5555-5555-555555555555", 0.55),
            ),
        ))

        // Seed artist — not owned. We still write the artist row because
        // the engine uses it to resolve similar-artist data for the seed.
        Artist(
            name = "The Police",
            sort_name = "Police, The",
            artist_type = ArtistType.GROUP.name,
            musicbrainz_artist_id = POLICE_MBID,
            created_at = now, updated_at = now
        ).also { it.save() }

        insertOwnedAlbum(
            artistName = "The Clash", artistMbid = CLASH_MBID,
            albumName = "London Calling", releaseYear = 1979,
            trackNames = listOf("London Calling", "Train in Vain", "Rudie Can't Fail"),
            genreName = "Punk"
        )
        insertOwnedAlbum(
            artistName = "The Cure", artistMbid = CURE_MBID,
            albumName = "Disintegration", releaseYear = 1989,
            trackNames = listOf("Plainsong", "Pictures of You", "Lovesong"),
            genreName = "Post-Punk"
        )
        insertOwnedAlbum(
            artistName = "Abba", artistMbid = ABBA_MBID,
            albumName = "Arrival", releaseYear = 1976,
            trackNames = listOf("Dancing Queen", "Money Money Money", "Knowing Me Knowing You"),
            genreName = "Pop"
        )

        // Seed "The Police / Zenyatta Mondatta" as an unowned album to seed
        // from. The engine only needs the Title row + TitleArtist pointing
        // at The Police, it doesn't require owned tracks under the seed.
        val seedAlbum = Title(
            name = "Zenyatta Mondatta",
            media_type = MediaType.ALBUM.name,
            release_year = 1980,
            musicbrainz_release_group_id = "99999999-9999-9999-9999-999999999999",
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now, updated_at = now
        ).also { it.save() }
        val policeArtistId = Artist.findAll().first { it.musicbrainz_artist_id == POLICE_MBID }.id!!
        TitleArtist(
            title_id = seedAlbum.id!!,
            artist_id = policeArtistId,
            artist_order = 0
        ).save()
    }

    private fun insertOwnedAlbum(
        artistName: String,
        artistMbid: String,
        albumName: String,
        releaseYear: Int,
        trackNames: List<String>,
        genreName: String,
    ) {
        val now = LocalDateTime.now()
        val artist = Artist(
            name = artistName,
            sort_name = artistName,
            artist_type = ArtistType.GROUP.name,
            musicbrainz_artist_id = artistMbid,
            created_at = now, updated_at = now
        ).also { it.save() }

        val album = Title(
            name = albumName,
            media_type = MediaType.ALBUM.name,
            release_year = releaseYear,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now, updated_at = now
        ).also { it.save() }

        TitleArtist(
            title_id = album.id!!,
            artist_id = artist.id!!,
            artist_order = 0
        ).save()

        trackNames.forEachIndexed { i, name ->
            Track(
                title_id = album.id!!,
                track_number = i + 1,
                disc_number = 1,
                name = name,
                file_path = "/fake/music/$artistName/$albumName/${i + 1}-$name.flac",
                created_at = now, updated_at = now
            ).save()
        }

        val genre = Genre.findAll().firstOrNull { it.name == genreName }
            ?: Genre(name = genreName).also { it.save() }
        TitleGenre(title_id = album.id!!, genre_id = genre.id!!).save()
    }

    private fun seedPoliceAlbumId(): Long =
        Title.findAll().first { it.name == "Zenyatta Mondatta" }.id!!

    @Test
    fun `startFromAlbum produces a batch drawn from owned similar artists`() {
        val batch = RadioService.startFromAlbum(seedPoliceAlbumId())
        assertNotNull(batch, "batch should not be null")
        assertTrue(batch.tracks.isNotEmpty(), "batch should have tracks")

        val artists = batch.tracks.mapNotNull { it.artistMbid }.toSet()
        assertTrue(CLASH_MBID in artists, "expected The Clash in batch; got $artists")
        assertTrue(CURE_MBID in artists, "expected The Cure in batch; got $artists")
        // Phase 1 fills with similar artists first; the top of the batch
        // should be entirely owned-similar before any fallback content
        // appears. Clash + Cure supply 6 tracks total, so inspecting the
        // first 6 is a tight bound on the phase-1 behaviour.
        val earlyArtists = batch.tracks.take(6).mapNotNull { it.artistMbid }.toSet()
        assertFalse(ABBA_MBID in earlyArtists,
            "Abba isn't in similar-artist list; should not be in the first 6 batch slots. Got $earlyArtists")
    }

    @Test
    fun `batch interleaves artists round-robin`() {
        val batch = RadioService.startFromAlbum(seedPoliceAlbumId())!!
        assertNotNull(batch)
        // First two tracks should be from different artists (round-robin).
        val first = batch.tracks.getOrNull(0)?.artistMbid
        val second = batch.tracks.getOrNull(1)?.artistMbid
        assertNotNull(first); assertNotNull(second)
        assertFalse(first == second,
            "round-robin: first and second tracks should be from different artists, both were $first")
    }

    @Test
    fun `skip-early down-weights the artist on the next batch`() {
        val first = RadioService.startFromAlbum(seedPoliceAlbumId())!!
        val seed = first.seed
        val firstClashTrack = first.tracks.first { it.artistMbid == CLASH_MBID }
        // Two early-skips for the same artist trip the EARLY_SKIP_THRESHOLD.
        val clashTracks = first.tracks.filter { it.artistMbid == CLASH_MBID }.take(2)
        assertTrue(clashTracks.size >= 2,
            "test fixture should produce at least 2 Clash tracks per batch; got ${clashTracks.size}")
        val history = clashTracks.map {
            RadioService.HistoryEntry(trackId = it.trackId, skippedEarly = true)
        }

        val next = RadioService.nextBatch(seed, history)
        val secondClash = next.tracks.count { it.artistMbid == CLASH_MBID }
        // After two early skips The Clash should be dropped from the
        // candidate list entirely — zero tracks in the follow-up batch.
        assertEquals(0, secondClash,
            "Clash should be weighted out after two early skips; got $secondClash tracks")
    }

    @Test
    fun `fallback cascade fills when similar intersection is empty`() {
        // Replace the stub with a response that points only at unowned
        // artists, forcing the fallback cascade.
        SimilarArtistService.lastFm = StubLastFm(mapOf(
            POLICE_MBID to listOf(
                LastFmSimilarArtist("Unknown", "66666666-6666-6666-6666-666666666666", 0.9),
            ),
        ))
        // Clear any cached similar json so the stub is consulted fresh.
        for (a in Artist.findAll()) {
            a.lastfm_similar_json = null
            a.similar_fetched_at = null
            a.save()
        }

        val batch = RadioService.startFromAlbum(seedPoliceAlbumId())!!
        assertTrue(batch.tracks.isNotEmpty(),
            "fallback should keep the queue non-empty when similar ∩ owned is zero")
    }
}
