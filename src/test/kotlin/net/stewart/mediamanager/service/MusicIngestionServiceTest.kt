package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackArtist
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MusicIngestionService] — physical-CD ingest, the
 * unmatched-audio admin manual-ingest path, and syncMissingTracks
 * for fuller-pressing follow-ups.
 */
class MusicIngestionServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:musicingesttest;DB_CLOSE_DELAY=-1"
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

    @Before
    fun reset() {
        // Children before parents to satisfy FKs.
        WishListItem.deleteAll()
        TrackArtist.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        UnmatchedAudio.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        AppUser.deleteAll()
    }

    private fun artistCredit(
        mbid: String = "artist-mbid-doors",
        name: String = "The Doors",
        type: String? = "Group",
        sortName: String = "Doors, The"
    ) = MusicBrainzArtistCredit(
        musicBrainzArtistId = mbid, name = name, type = type, sortName = sortName
    )

    private fun mbTrack(
        position: Int,
        name: String = "Track $position",
        durationSeconds: Int? = 180,
        trackArtistCredits: List<MusicBrainzArtistCredit> = emptyList()
    ) = MusicBrainzTrack(
        musicBrainzRecordingId = "rec-$position",
        trackNumber = position,
        discNumber = 1,
        name = name,
        durationSeconds = durationSeconds,
        trackArtistCredits = trackArtistCredits
    )

    private fun lookup(
        rgid: String = "rg-la-woman",
        releaseId: String = "rel-la-woman-1971",
        title: String = "L.A. Woman",
        barcode: String? = null,
        albumArtists: List<MusicBrainzArtistCredit> = listOf(artistCredit()),
        tracks: List<MusicBrainzTrack> = listOf(mbTrack(1, "The Changeling"), mbTrack(2, "Riders on the Storm"))
    ) = MusicBrainzReleaseLookup(
        musicBrainzReleaseId = releaseId,
        musicBrainzReleaseGroupId = rgid,
        title = title,
        albumArtistCredits = albumArtists,
        releaseYear = 1971,
        label = "Elektra",
        barcode = barcode,
        tracks = tracks,
        totalDurationSeconds = tracks.sumOf { it.durationSeconds ?: 0 }.takeIf { it > 0 },
        rawJson = "{}"
    )

    // ---------------------- ingest ----------------------

    @Test
    fun `ingest creates Title Artist Tracks MediaItem and MediaItemTitle on first call`() {
        val result = MusicIngestionService.ingest(
            upc = "0603497905225",
            mediaFormat = MediaFormat.CD,
            lookup = lookup()
        )
        assertEquals(false, result.titleReused, "first call must not be a reuse")

        val title = Title.findAll().single()
        assertEquals("L.A. Woman", title.name)
        assertEquals(MediaType.ALBUM.name, title.media_type)
        assertEquals("rg-la-woman", title.musicbrainz_release_group_id)
        assertEquals("rel-la-woman-1971", title.musicbrainz_release_id)
        assertEquals("caa/rel-la-woman-1971", title.poster_path,
            "poster path keys on the specific pressing MBID")
        assertEquals(2, title.track_count)

        val artist = Artist.findAll().single()
        assertEquals("The Doors", artist.name)
        assertEquals("artist-mbid-doors", artist.musicbrainz_artist_id)
        assertEquals(ArtistType.GROUP.name, artist.artist_type)

        // TitleArtist link with order 0.
        val ta = TitleArtist.findAll().single()
        assertEquals(title.id, ta.title_id)
        assertEquals(artist.id, ta.artist_id)
        assertEquals(0, ta.artist_order)

        val tracks = Track.findAll().filter { it.title_id == title.id }
            .sortedBy { it.track_number }
        assertEquals(listOf(1, 2), tracks.map { it.track_number })
        assertEquals("The Changeling", tracks[0].name)

        val mediaItem = MediaItem.findAll().single()
        assertEquals("0603497905225", mediaItem.upc)
        assertEquals(MediaFormat.CD.name, mediaItem.media_format)
        assertEquals(1, MediaItemTitle.findAll().count {
            it.media_item_id == mediaItem.id && it.title_id == title.id
        })
    }

    @Test
    fun `ingest reuses Title for the same release-group on a second pressing`() {
        val first = MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD, lookup = lookup()
        )
        // Second pressing — same RGID, different release id.
        val second = MusicIngestionService.ingest(
            upc = "222", mediaFormat = MediaFormat.CD,
            lookup = lookup(releaseId = "rel-la-woman-2007")
        )
        assertTrue(second.titleReused)
        assertEquals(first.title.id, second.title.id)

        // Only one Title; two MediaItem rows; tracklist not duplicated.
        assertEquals(1, Title.findAll().size)
        assertEquals(2, MediaItem.findAll().size)
        assertEquals(2, Track.findAll().count { it.title_id == first.title.id },
            "tracklist not duplicated when reusing an existing title")
        // Artist not duplicated either.
        assertEquals(1, Artist.findAll().size)
    }

    @Test
    fun `ingest falls back to lookup barcode when upc is null`() {
        MusicIngestionService.ingest(
            upc = null, mediaFormat = MediaFormat.CD,
            lookup = lookup(rgid = "rg-other").copy(barcode = "0888888888888")
        )
        assertEquals("0888888888888", MediaItem.findAll().single().upc)
    }

    @Test
    fun `ingest creates per-track artist when track credit differs from album credit`() {
        val voActor = artistCredit("artist-vo", "Jim Morrison", "Person", "Morrison, Jim")
        val tracksWithFeature = listOf(
            mbTrack(1, "The Changeling"),
            mbTrack(2, "Riders on the Storm",
                trackArtistCredits = listOf(voActor))
        )
        MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD,
            lookup = lookup(tracks = tracksWithFeature)
        )

        // Artist count: album artist + per-track artist = 2.
        assertEquals(2, Artist.findAll().size)
        val solo = Artist.findAll().single { it.musicbrainz_artist_id == "artist-vo" }
        val track2 = Track.findAll().single { it.track_number == 2 }
        val ta = TrackArtist.findAll().single { it.track_id == track2.id }
        assertEquals(solo.id, ta.artist_id)
    }

    @Test
    fun `ingest fulfills active album wishes for the same release-group across users`() {
        val u = AppUser(username = "u", display_name = "U", password_hash = "x")
            .apply { save() }.id!!
        WishListService.addAlbumWishForUser(u, WishListService.AlbumWishInput(
            musicBrainzReleaseGroupId = "rg-la-woman",
            title = "L.A. Woman",
            primaryArtist = "The Doors",
            year = 1971,
            coverReleaseId = null,
            isCompilation = false,
        ))
        assertEquals(1, WishListItem.findAll().count { it.status == WishStatus.ACTIVE.name })

        MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD, lookup = lookup()
        )

        val wish = WishListItem.findAll().single()
        assertEquals(WishStatus.FULFILLED.name, wish.status)
        assertNotNull(wish.fulfilled_at)
    }

    @Test
    fun `ingest maps MB artist types covering all known and forward-compat values`() {
        // Run ingest with a single artist of each type to drive the mapArtistType
        // branches without making 5 separate albums interfere with each other.
        val cases = mapOf(
            "Person" to ArtistType.PERSON.name,
            "Group" to ArtistType.GROUP.name,
            "Orchestra" to ArtistType.ORCHESTRA.name,
            "Choir" to ArtistType.CHOIR.name,
            "Other" to ArtistType.OTHER.name,
            null to ArtistType.GROUP.name,           // legacy MB rows
            "Klingon Battle Hymn Ensemble" to ArtistType.OTHER.name, // forward-compat
        )

        cases.entries.forEachIndexed { i, (mbType, expected) ->
            val rg = "rg-$i"
            MusicIngestionService.ingest(
                upc = "upctype00$i", mediaFormat = MediaFormat.CD,
                lookup = lookup(
                    rgid = rg, releaseId = "rel-$i", title = "Album $i",
                    albumArtists = listOf(artistCredit(
                        mbid = "mb-$i", name = "Artist $i", type = mbType,
                        sortName = "Artist $i"
                    )),
                    tracks = listOf(mbTrack(1, "T")),
                ),
            )
            val artist = Artist.findAll().single { it.musicbrainz_artist_id == "mb-$i" }
            assertEquals(expected, artist.artist_type,
                "MB type=$mbType should map to $expected")
        }
    }

    @Test
    fun `ingest titleSortName strips leading articles`() {
        // "The X" -> sort_name "X"; "An X" -> "X"; "A X" -> "X".
        listOf(
            "The Wall" to "Wall",
            "An Innocent Man" to "Innocent Man",
            "A Hard Day's Night" to "Hard Day's Night",
            "Pet Sounds" to "Pet Sounds",
        ).forEachIndexed { i, (raw, expectedSort) ->
            val rg = "rg-sort-$i"
            MusicIngestionService.ingest(
                upc = "upcsort00$i", mediaFormat = MediaFormat.CD,
                lookup = lookup(rgid = rg, releaseId = "rel-sort-$i", title = raw),
            )
            val title = Title.findAll().single { it.musicbrainz_release_group_id == rg }
            assertEquals(expectedSort, title.sort_name, "sort form for '$raw'")
        }
    }

    // ---------------------- syncMissingTracks ----------------------

    @Test
    fun `syncMissingTracks adds only the positions not already present and returns the count`() {
        // Initial ingest with tracks 1 and 2.
        val initial = MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD,
            lookup = lookup(tracks = listOf(mbTrack(1, "A"), mbTrack(2, "B")))
        )

        // Larger pressing with 4 tracks. syncMissingTracks should add 3 and 4.
        val expanded = lookup(tracks = listOf(
            mbTrack(1, "A"), mbTrack(2, "B"), mbTrack(3, "C"), mbTrack(4, "D")
        ))
        val added = MusicIngestionService.syncMissingTracks(initial.title.id!!, expanded)
        assertEquals(2, added)

        val numbers = Track.findAll().filter { it.title_id == initial.title.id }
            .map { it.track_number }.toSet()
        assertEquals(setOf(1, 2, 3, 4), numbers)
    }

    @Test
    fun `syncMissingTracks returns zero when nothing is missing`() {
        val initial = MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD,
            lookup = lookup(tracks = listOf(mbTrack(1, "A"), mbTrack(2, "B")))
        )
        val added = MusicIngestionService.syncMissingTracks(initial.title.id!!, lookup(
            tracks = listOf(mbTrack(1, "A"), mbTrack(2, "B"))
        ))
        assertEquals(0, added)
    }

    @Test
    fun `syncMissingTracks creates per-track artist when present`() {
        val initial = MusicIngestionService.ingest(
            upc = "111", mediaFormat = MediaFormat.CD,
            lookup = lookup(tracks = listOf(mbTrack(1, "A")))
        )
        val featured = artistCredit("guest", "Guest Star", "Person", "Star, Guest")
        MusicIngestionService.syncMissingTracks(initial.title.id!!, lookup(
            tracks = listOf(mbTrack(1, "A"),
                mbTrack(2, "B", trackArtistCredits = listOf(featured)))
        ))
        val track2 = Track.findAll().single { it.track_number == 2 }
        val ta = TrackArtist.findAll().single { it.track_id == track2.id }
        val guest = Artist.findAll().single { it.musicbrainz_artist_id == "guest" }
        assertEquals(guest.id, ta.artist_id)
    }

    // ---------------------- ingestManualFromRows ----------------------

    private fun row(
        track: Int = 1,
        disc: Int = 1,
        title: String? = "Track $track",
        album: String? = "My Album",
        albumArtist: String? = null,
        trackArtist: String? = null,
        durationSeconds: Int? = 180,
        recordingMbid: String? = null,
        label: String? = null,
        fileName: String = "track-$track.flac"
    ) = UnmatchedAudio(
        file_path = "/music/$fileName",
        file_name = fileName,
        parsed_title = title,
        parsed_album = album,
        parsed_album_artist = albumArtist,
        parsed_track_artist = trackArtist,
        parsed_track_number = track,
        parsed_disc_number = disc,
        parsed_duration_seconds = durationSeconds,
        parsed_mb_recording_id = recordingMbid,
        parsed_label = label,
    )

    @Test
    fun `ingestManualFromRows creates Title with explicit album_artist`() {
        val rows = listOf(
            row(1, albumArtist = "Pink Floyd", trackArtist = "Pink Floyd"),
            row(2, albumArtist = "Pink Floyd", trackArtist = "Pink Floyd"),
        )
        val title = MusicIngestionService.ingestManualFromRows(rows)
        assertEquals("My Album", title.name)
        assertEquals(MediaType.ALBUM.name, title.media_type)
        assertEquals(2, title.track_count)
        assertEquals(360, title.total_duration_seconds)
        assertNull(title.musicbrainz_release_group_id,
            "manual ingest leaves the MB key blank — never merges with future MB-sourced pressings")

        val artist = Artist.findAll().single()
        assertEquals("Pink Floyd", artist.name)
        // No per-track artist row when track artist matches album artist.
        assertEquals(0, TrackArtist.findAll().size)
    }

    @Test
    fun `ingestManualFromRows infers single-artist album when no album_artist tag`() {
        val rows = listOf(
            row(1, trackArtist = "Solo Artist"),
            row(2, trackArtist = "Solo Artist"),
        )
        val title = MusicIngestionService.ingestManualFromRows(rows)
        assertEquals("Solo Artist", Artist.findAll().single().name)
        assertEquals(0, TrackArtist.findAll().size,
            "track-artist matching album-artist suppresses per-track row")
        // Title is linked to the inferred artist.
        val ta = TitleArtist.findAll().single { it.title_id == title.id }
        assertEquals(Artist.findAll().single().id, ta.artist_id)
    }

    @Test
    fun `ingestManualFromRows falls back to Various Artists for compilations`() {
        val rows = listOf(
            row(1, trackArtist = "Artist A"),
            row(2, trackArtist = "Artist B"),
        )
        val title = MusicIngestionService.ingestManualFromRows(rows)
        // 3 artists: "Various Artists" (album) + "Artist A" + "Artist B" (per-track).
        val names = Artist.findAll().map { it.name }.toSet()
        assertEquals(setOf("Various Artists", "Artist A", "Artist B"), names)
        // Each track has its own TrackArtist row pointing at its real artist.
        assertEquals(2, TrackArtist.findAll().size)
        // Title -> "Various Artists" album link.
        val albumArtist = Artist.findAll().single { it.name == "Various Artists" }
        assertEquals(albumArtist.id, TitleArtist.findAll().single { it.title_id == title.id }.artist_id)
    }

    @Test
    fun `ingestManualFromRows preserves track number gaps`() {
        // Disc 2, tracks 5..7 only — keep the gaps, don't renumber.
        val rows = listOf(
            row(track = 5, disc = 2),
            row(track = 6, disc = 2),
            row(track = 7, disc = 2),
        )
        val title = MusicIngestionService.ingestManualFromRows(rows)
        val tracks = Track.findAll().filter { it.title_id == title.id }
            .sortedBy { it.track_number }
        assertEquals(listOf(5, 6, 7), tracks.map { it.track_number })
        assertTrue(tracks.all { it.disc_number == 2 })
    }

    @Test
    fun `ingestManualFromRows uses file_name when parsed_title is blank`() {
        val rows = listOf(row(1, title = ""))
        val title = MusicIngestionService.ingestManualFromRows(rows)
        val track = Track.findAll().single { it.title_id == title.id }
        assertEquals("track-1.flac", track.name)
    }

    @Test
    fun `ingestManualFromRows requires non-empty rows`() {
        assertFailsWith<IllegalArgumentException> {
            MusicIngestionService.ingestManualFromRows(emptyList())
        }
    }

    @Test
    fun `ingestManualFromRows requires a parsed_album`() {
        val rows = listOf(row(1, album = null))
        assertFailsWith<IllegalArgumentException> {
            MusicIngestionService.ingestManualFromRows(rows)
        }
    }

    @Test
    fun `ingestManualFromRows uses the dominant value across rows for label`() {
        val rows = listOf(
            row(1, label = "Label A"),
            row(2, label = "Label A"),
            row(3, label = "Label B"),
        )
        val title = MusicIngestionService.ingestManualFromRows(rows)
        assertEquals("Label A", title.label, "most-common label wins")
    }
}
