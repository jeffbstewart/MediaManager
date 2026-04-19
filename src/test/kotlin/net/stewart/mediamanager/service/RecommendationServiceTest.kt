package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.RecommendedArtist
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the M8 recommendation pipeline against a controlled owned
 * library. See docs/MUSIC.md §M8.
 *
 * Fixture:
 *  - **Clash**: owned × 3 albums — heavy voter.
 *  - **Cure**:  owned × 1 album  — light voter.
 *  - **Abba**:  owned × 1 album  — unrelated voter.
 *  - Last.fm stub returns:
 *      Clash → Blondie (0.8), Ramones (0.6)
 *      Cure  → Blondie (0.4), Depeche (0.9)
 *      Abba  → Bee Gees (0.7)
 *  - Nothing MB-backed for the representative-release lookup (the
 *    stub returns empty); that path is tested for side-effects only.
 */
class RecommendationServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:rectest;DB_CLOSE_DELAY=-1"
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

        private const val CLASH_MBID    = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val CURE_MBID     = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ABBA_MBID     = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        private const val BLONDIE_MBID  = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        private const val RAMONES_MBID  = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        private const val DEPECHE_MBID  = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        private const val BEEGEES_MBID  = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
    }

    private lateinit var user: AppUser

    private class StubLastFm(private val responses: Map<String, List<LastFmSimilarArtist>>) : LastFmService {
        override fun fetchSimilarArtists(artistMbid: String, limit: Int): LastFmResult {
            val s = responses[artistMbid] ?: return LastFmResult.NotFound
            val json = "{\"similarartists\":{\"artist\":[]}}"  // not read back by the test
            return LastFmResult.Success(s, json)
        }
    }

    /** MB stub — returns no release groups. Keeps the service off the network. */
    private class StubMusicBrainz : MusicBrainzService {
        override fun lookupByBarcode(barcode: String) = MusicBrainzResult.NotFound
        override fun lookupByReleaseMbid(releaseMbid: String) = MusicBrainzResult.NotFound
        override fun listArtistReleaseGroups(artistMbid: String, limit: Int) =
            emptyList<ArtistReleaseGroupRef>()
        override fun listReleaseRecordingCredits(releaseMbid: String) =
            emptyList<MusicBrainzRecordingCredit>()
        override fun listArtistMemberships(artistMbid: String) =
            emptyList<MusicBrainzMembership>()
        override fun searchByCatalogNumber(catalogNumber: String, label: String?) =
            emptyList<String>()
        override fun searchByIsrc(isrc: String) = emptyList<String>()
        override fun searchReleaseByArtistAndAlbum(albumArtist: String, album: String) =
            emptyList<String>()
    }

    @Before
    fun seedFixture() {
        // FK-safe cleanup order.
        RecommendedArtist.deleteAll()
        MediaItemTitle.deleteAll()
        TitleArtist.deleteAll()
        TitleGenre.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        AppUser.deleteAll()

        val now = LocalDateTime.now()

        user = AppUser(
            username = "rectestuser",
            display_name = "Rec Test",
            password_hash = "x",
            access_level = 2,
            created_at = now, updated_at = now
        ).also { it.save() }

        SimilarArtistService.lastFm = StubLastFm(mapOf(
            CLASH_MBID to listOf(
                LastFmSimilarArtist("Blondie", BLONDIE_MBID, 0.8),
                LastFmSimilarArtist("Ramones", RAMONES_MBID, 0.6),
            ),
            CURE_MBID to listOf(
                LastFmSimilarArtist("Blondie", BLONDIE_MBID, 0.4),
                LastFmSimilarArtist("Depeche Mode", DEPECHE_MBID, 0.9),
            ),
            ABBA_MBID to listOf(
                LastFmSimilarArtist("Bee Gees", BEEGEES_MBID, 0.7),
            ),
        ))

        insertOwnedAlbum("The Clash",  CLASH_MBID, "London Calling", 1979)
        insertOwnedAlbum("The Clash",  CLASH_MBID, "Combat Rock",    1982)
        insertOwnedAlbum("The Clash",  CLASH_MBID, "Sandinista!",    1980)
        insertOwnedAlbum("The Cure",   CURE_MBID,  "Disintegration", 1989)
        insertOwnedAlbum("Abba",       ABBA_MBID,  "Arrival",        1976)
    }

    private fun insertOwnedAlbum(
        artistName: String, artistMbid: String,
        albumName: String, year: Int
    ) {
        val now = LocalDateTime.now()
        val artist = Artist.findAll().firstOrNull { it.musicbrainz_artist_id == artistMbid }
            ?: Artist(
                name = artistName, sort_name = artistName,
                artist_type = ArtistType.GROUP.name,
                musicbrainz_artist_id = artistMbid,
                created_at = now, updated_at = now
            ).also { it.save() }

        val album = Title(
            name = albumName,
            media_type = MediaType.ALBUM.name,
            release_year = year,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now, updated_at = now
        ).also { it.save() }

        TitleArtist(
            title_id = album.id!!,
            artist_id = artist.id!!,
            artist_order = 0
        ).save()

        Track(
            title_id = album.id!!,
            track_number = 1, disc_number = 1,
            name = "Track 1",
            file_path = "/fake/$artistName/$albumName/01.flac",
            created_at = now, updated_at = now
        ).save()
    }

    @Test
    fun `recompute produces ranked suggestions from owned library`() {
        val count = RecommendationService.recompute(user.id!!, StubMusicBrainz())
        assertTrue(count > 0, "recompute should produce at least one suggestion")

        val recs = RecommendedArtist.findAll()
            .filter { it.user_id == user.id }
            .sortedByDescending { it.score }
        val mbids = recs.map { it.suggested_artist_mbid }

        // All three unowned artists should appear.
        assertTrue(BLONDIE_MBID in mbids, "Blondie missing; got $mbids")
        assertTrue(RAMONES_MBID in mbids, "Ramones missing; got $mbids")
        assertTrue(DEPECHE_MBID in mbids, "Depeche missing; got $mbids")
        assertTrue(BEEGEES_MBID in mbids, "Bee Gees missing; got $mbids")

        // Owned artists must not be recommended back.
        assertTrue(CLASH_MBID !in mbids)
        assertTrue(CURE_MBID !in mbids)
        assertTrue(ABBA_MBID !in mbids)
    }

    @Test
    fun `vote weighting favours artists with more owned albums`() {
        RecommendationService.recompute(user.id!!, StubMusicBrainz())
        val recs = RecommendedArtist.findAll().filter { it.user_id == user.id }

        // Clash (3 albums) votes for Blondie at 0.8 → 2.4; Cure (1 album)
        // votes Blondie at 0.4 → 0.4; total 2.8.
        // Depeche: Cure (1 album) × 0.9 = 0.9.
        // Ramones: Clash (3) × 0.6 = 1.8.
        // Bee Gees: Abba (1) × 0.7 = 0.7.
        // Expected order: Blondie (2.8) > Ramones (1.8) > Depeche (0.9) > Bee Gees (0.7).
        val blondie = recs.first { it.suggested_artist_mbid == BLONDIE_MBID }
        val ramones = recs.first { it.suggested_artist_mbid == RAMONES_MBID }
        val depeche = recs.first { it.suggested_artist_mbid == DEPECHE_MBID }
        assertTrue(blondie.score > ramones.score,
            "Blondie (${blondie.score}) should outrank Ramones (${ramones.score})")
        assertTrue(ramones.score > depeche.score,
            "Ramones (${ramones.score}) should outrank Depeche (${depeche.score})")
    }

    @Test
    fun `top voters explain each suggestion`() {
        RecommendationService.recompute(user.id!!, StubMusicBrainz())
        val blondie = RecommendedArtist.findAll()
            .first { it.user_id == user.id && it.suggested_artist_mbid == BLONDIE_MBID }
        assertNotNull(blondie.voters_json)
        val json = blondie.voters_json!!
        // Clash and Cure both voted for Blondie; Clash should appear first
        // (more albums). voters_json is a JSON array of {mbid, name, album_count}.
        assertTrue(json.contains("The Clash"), "voters_json missing Clash: $json")
        assertTrue(json.contains("The Cure"), "voters_json missing Cure: $json")
        val clashIdx = json.indexOf("The Clash")
        val cureIdx = json.indexOf("The Cure")
        assertTrue(clashIdx in 0 until cureIdx,
            "Clash should precede Cure in voter order (more owned albums): $json")
    }

    @Test
    fun `dismissal persists across a recompute`() {
        RecommendationService.recompute(user.id!!, StubMusicBrainz())
        val blondie = RecommendedArtist.findAll()
            .first { it.user_id == user.id && it.suggested_artist_mbid == BLONDIE_MBID }
        blondie.dismissed_at = LocalDateTime.now()
        blondie.save()

        RecommendationService.recompute(user.id!!, StubMusicBrainz())
        val after = RecommendedArtist.findAll()
            .firstOrNull { it.user_id == user.id && it.suggested_artist_mbid == BLONDIE_MBID }
        assertNotNull(after, "dismissed row should survive recompute")
        assertNotNull(after.dismissed_at, "dismissal timestamp should survive recompute")
    }

    @Test
    fun `prune removes active rows that fall off the top-N`() {
        // Insert a stale active row for a bogus artist; the recompute
        // should delete it because it isn't in the current aggregate.
        val bogus = RecommendedArtist(
            user_id = user.id!!,
            suggested_artist_mbid = "99999999-9999-9999-9999-999999999999",
            suggested_artist_name = "Bogus Band",
            score = 0.01,
            created_at = LocalDateTime.now()
        ).also { it.save() }
        assertNotNull(bogus.id)

        RecommendationService.recompute(user.id!!, StubMusicBrainz())
        val stillThere = RecommendedArtist.findAll()
            .firstOrNull { it.user_id == user.id && it.suggested_artist_mbid == bogus.suggested_artist_mbid }
        assertNull(stillThere, "stale non-dismissed row should be pruned")
    }
}
