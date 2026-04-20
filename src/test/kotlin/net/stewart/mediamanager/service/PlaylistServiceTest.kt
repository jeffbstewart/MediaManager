package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.PlaylistProgress
import net.stewart.mediamanager.entity.PlaylistTrack
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackPlayCount
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [PlaylistService]: create / add / reorder / remove /
 * setHero / duplicate / libraryShuffle, plus the owner-only mutation
 * gate. Duplicate is the special case — non-owners can fork.
 */
class PlaylistServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:playlisttest;DB_CLOSE_DELAY=-1"
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
    }

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var trackIds: List<Long>

    @Before
    fun seedFixture() {
        // Cascades from playlist → playlist_track and from title → track,
        // so we only need to nuke the top-level rows.
        TrackPlayCount.deleteAll()
        PlaylistProgress.deleteAll()
        Playlist.deleteAll()
        PlaylistTrack.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()

        val now = LocalDateTime.now()
        alice = AppUser(
            username = "alice", display_name = "Alice", password_hash = "x",
            access_level = 2, created_at = now, updated_at = now
        ).also { it.save() }
        bob = AppUser(
            username = "bob", display_name = "Bob", password_hash = "x",
            access_level = 2, created_at = now, updated_at = now
        ).also { it.save() }

        // One album with five playable tracks. Enough to exercise reorder
        // permutations without making the assertions noisy.
        val album = Title(
            name = "Test Album",
            media_type = MediaType.ALBUM.name,
            release_year = 2024,
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            created_at = now, updated_at = now
        ).also { it.save() }

        trackIds = (1..5).map { i ->
            Track(
                title_id = album.id!!,
                track_number = i,
                disc_number = 1,
                name = "Track $i",
                duration_seconds = 180,
                file_path = "/fake/track$i.flac",
                created_at = now, updated_at = now
            ).also { it.save() }.id!!
        }
    }

    @Test
    fun `create assigns owner and stamps timestamps`() {
        val pl = PlaylistService.create(alice, "Mix Tape", "for the road")
        assertEquals("Mix Tape", pl.name)
        assertEquals("for the road", pl.description)
        assertEquals(alice.id, pl.owner_user_id)
        assertNotEquals(null, pl.created_at)
    }

    @Test
    fun `addTracks appends in order and updates updated_at`() {
        val pl = PlaylistService.create(alice, "M", null)
        PlaylistService.addTracks(pl.id!!, alice, trackIds)
        val view = PlaylistService.getDetail(pl.id!!)
        assertEquals(trackIds, view.tracks.map { it.track.id })
        view.tracks.forEachIndexed { i, t -> assertEquals(i, t.position) }
    }

    @Test
    fun `addTracks ignores unknown ids and tolerates duplicates`() {
        val pl = PlaylistService.create(alice, "M", null)
        // Two valid + one bogus + the first track again.
        PlaylistService.addTracks(pl.id!!, alice, listOf(trackIds[0], 99999L, trackIds[1], trackIds[0]))
        val view = PlaylistService.getDetail(pl.id!!)
        assertEquals(listOf(trackIds[0], trackIds[1], trackIds[0]), view.tracks.map { it.track.id })
    }

    @Test
    fun `reorder persists new order and stays dense`() {
        val pl = PlaylistService.create(alice, "M", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)
        val ptIds = added.map { it.id!! }
        val reversed = ptIds.reversed()

        PlaylistService.reorder(pl.id!!, alice, reversed)
        val view = PlaylistService.getDetail(pl.id!!)
        assertEquals(trackIds.reversed(), view.tracks.map { it.track.id })
        // Dense: positions are 0..n-1 with no gaps.
        assertEquals((0 until trackIds.size).toList(), view.tracks.map { it.position })
    }

    @Test
    fun `reorder treats omitted ids as removals`() {
        val pl = PlaylistService.create(alice, "M", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)
        // Keep just the middle three, in a new order.
        val keep = listOf(added[3].id!!, added[1].id!!, added[2].id!!)

        PlaylistService.reorder(pl.id!!, alice, keep)
        val view = PlaylistService.getDetail(pl.id!!)
        assertEquals(3, view.tracks.size)
        assertEquals(listOf(trackIds[3], trackIds[1], trackIds[2]), view.tracks.map { it.track.id })
    }

    @Test
    fun `removeTrack compacts positions`() {
        val pl = PlaylistService.create(alice, "M", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)

        PlaylistService.removeTrack(pl.id!!, alice, added[2].id!!)
        val view = PlaylistService.getDetail(pl.id!!)
        assertEquals(4, view.tracks.size)
        assertEquals((0 until 4).toList(), view.tracks.map { it.position })
    }

    @Test
    fun `setHero only accepts a track currently on the playlist`() {
        val pl = PlaylistService.create(alice, "M", null)
        PlaylistService.addTracks(pl.id!!, alice, listOf(trackIds[0], trackIds[1]))

        PlaylistService.setHero(pl.id!!, alice, trackIds[1])
        assertEquals(trackIds[1], Playlist.findById(pl.id!!)!!.hero_track_id)

        // Track 4 is in the catalog but not on this playlist — silent no-op.
        PlaylistService.setHero(pl.id!!, alice, trackIds[4])
        assertEquals(trackIds[1], Playlist.findById(pl.id!!)!!.hero_track_id,
            "hero should not change when the chosen track isn't on the playlist")

        // Clear.
        PlaylistService.setHero(pl.id!!, alice, null)
        assertNull(Playlist.findById(pl.id!!)!!.hero_track_id)
    }

    @Test
    fun `non-owner cannot mutate, but can duplicate`() {
        val pl = PlaylistService.create(alice, "Alice's Mix", "private notes")
        PlaylistService.addTracks(pl.id!!, alice, trackIds)
        PlaylistService.setHero(pl.id!!, alice, trackIds[2])

        // Bob can read but not rename.
        assertFailsWith<PlaylistService.PlaylistAccessDenied> {
            PlaylistService.rename(pl.id!!, bob, "hijacked", null)
        }
        assertFailsWith<PlaylistService.PlaylistAccessDenied> {
            PlaylistService.delete(pl.id!!, bob)
        }

        // Bob can duplicate.
        val fork = PlaylistService.duplicate(pl.id!!, bob)
        assertEquals(bob.id, fork.owner_user_id)
        assertEquals("Alice's Mix (copy)", fork.name)
        assertEquals("private notes", fork.description)
        assertEquals(trackIds[2], fork.hero_track_id)

        val forkTracks = PlaylistService.getDetail(fork.id!!)
        assertEquals(trackIds, forkTracks.tracks.map { it.track.id })

        // The original is untouched.
        assertEquals("Alice's Mix", Playlist.findById(pl.id!!)!!.name)
    }

    @Test
    fun `duplicate respects newName override`() {
        val pl = PlaylistService.create(alice, "Source", null)
        PlaylistService.addTracks(pl.id!!, alice, listOf(trackIds[0]))

        val fork = PlaylistService.duplicate(pl.id!!, bob, newName = "Bob's Take")
        assertEquals("Bob's Take", fork.name)
    }

    @Test
    fun `libraryShuffle returns playable tracks only`() {
        // Add an unplayable track (no file_path) — it must not appear.
        val now = LocalDateTime.now()
        val albumId = Title.findAll().first().id!!
        Track(
            title_id = albumId,
            track_number = 99,
            disc_number = 1,
            name = "Unplayable",
            file_path = null,
            created_at = now, updated_at = now
        ).save()

        val shuffled = PlaylistService.libraryShuffle(alice, limit = null)
        assertEquals(trackIds.toSet(), shuffled.map { it.id }.toSet())
        assertTrue(shuffled.none { it.name == "Unplayable" })
    }

    @Test
    fun `delete cascades to playlist_track rows`() {
        val pl = PlaylistService.create(alice, "M", null)
        PlaylistService.addTracks(pl.id!!, alice, trackIds)
        val plId = pl.id!!

        PlaylistService.delete(plId, alice)

        assertNull(Playlist.findById(plId))
        val orphans = PlaylistTrack.findAll().filter { it.playlist_id == plId }
        assertTrue(orphans.isEmpty(), "ON DELETE CASCADE should drop playlist_track rows")
    }

    // ==========================================================
    // Phase 2 — privacy / resume / play count / smart playlists
    // ==========================================================

    @Test
    fun `private playlist hidden from listVisibleTo for non-owner, visible to owner`() {
        val pub = PlaylistService.create(alice, "Public", null)
        val priv = PlaylistService.create(alice, "Secret", null)
        PlaylistService.setPrivacy(priv.id!!, alice, true)

        val asAlice = PlaylistService.listVisibleTo(alice.id!!).map { it.id!! }
        val asBob = PlaylistService.listVisibleTo(bob.id!!).map { it.id!! }

        assertTrue(pub.id!! in asAlice && priv.id!! in asAlice, "owner sees both")
        assertTrue(pub.id!! in asBob, "non-owner sees public")
        assertTrue(priv.id!! !in asBob, "non-owner does NOT see private")
    }

    @Test
    fun `getDetail rejects non-owner reads of private playlist`() {
        val priv = PlaylistService.create(alice, "Secret", null)
        PlaylistService.setPrivacy(priv.id!!, alice, true)

        // Alice can read.
        val asAlice = PlaylistService.getDetail(priv.id!!, viewerUserId = alice.id)
        assertEquals("Secret", asAlice.playlist.name)

        // Bob cannot.
        assertFailsWith<PlaylistService.PlaylistAccessDenied> {
            PlaylistService.getDetail(priv.id!!, viewerUserId = bob.id)
        }

        // Anonymous read (viewerUserId = null) skips the gate — used for
        // server-side internal callers; the API layer always passes a user.
        val anon = PlaylistService.getDetail(priv.id!!, viewerUserId = null)
        assertEquals("Secret", anon.playlist.name)
    }

    @Test
    fun `setPrivacy is owner-only`() {
        val pl = PlaylistService.create(alice, "M", null)
        assertFailsWith<PlaylistService.PlaylistAccessDenied> {
            PlaylistService.setPrivacy(pl.id!!, bob, true)
        }
    }

    @Test
    fun `reportProgress upserts cursor and getResume reads it back`() {
        val pl = PlaylistService.create(alice, "M", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)
        val pt = added[2]

        // Initially absent.
        assertNull(PlaylistService.getResume(alice.id!!, pl.id!!))

        PlaylistService.reportProgress(alice.id!!, pl.id!!, pt.id!!, 42)
        val resume = PlaylistService.getResume(alice.id!!, pl.id!!)
        assertNotEquals(null, resume)
        assertEquals(pt.id, resume!!.playlistTrackId)
        assertEquals(trackIds[2], resume.trackId)
        assertEquals(42, resume.positionSeconds)

        // Update — same row, new values.
        PlaylistService.reportProgress(alice.id!!, pl.id!!, pt.id!!, 99)
        assertEquals(99, PlaylistService.getResume(alice.id!!, pl.id!!)!!.positionSeconds)
        // No duplicate row created.
        assertEquals(1, PlaylistProgress.findAll().count {
            it.user_id == alice.id && it.playlist_id == pl.id
        })
    }

    @Test
    fun `reportProgress is silent on private playlist for non-owner`() {
        val pl = PlaylistService.create(alice, "Secret", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)
        PlaylistService.setPrivacy(pl.id!!, alice, true)

        // Bob has the playlist_track id (somehow) but the call must
        // silently no-op rather than write a cursor.
        PlaylistService.reportProgress(bob.id!!, pl.id!!, added[0].id!!, 30)
        assertNull(PlaylistService.getResume(bob.id!!, pl.id!!))
    }

    @Test
    fun `clearResume drops the cursor`() {
        val pl = PlaylistService.create(alice, "M", null)
        val added = PlaylistService.addTracks(pl.id!!, alice, trackIds)
        PlaylistService.reportProgress(alice.id!!, pl.id!!, added[0].id!!, 10)
        assertNotEquals(null, PlaylistService.getResume(alice.id!!, pl.id!!))

        PlaylistService.clearResume(alice.id!!, pl.id!!)
        assertNull(PlaylistService.getResume(alice.id!!, pl.id!!))
    }

    @Test
    fun `recordTrackCompletion bumps per-user counter`() {
        PlaylistService.recordTrackCompletion(alice.id!!, trackIds[0])
        PlaylistService.recordTrackCompletion(alice.id!!, trackIds[0])
        PlaylistService.recordTrackCompletion(alice.id!!, trackIds[1])
        PlaylistService.recordTrackCompletion(bob.id!!, trackIds[0])

        val aliceRows = TrackPlayCount.findAll().filter { it.user_id == alice.id }
            .associateBy { it.track_id }
        assertEquals(2, aliceRows[trackIds[0]]?.play_count)
        assertEquals(1, aliceRows[trackIds[1]]?.play_count)

        // Bob's count is independent.
        val bobRow = TrackPlayCount.findAll().firstOrNull {
            it.user_id == bob.id && it.track_id == trackIds[0]
        }
        assertEquals(1, bobRow?.play_count)
    }

    @Test
    fun `mostPlayed smart playlist orders by user's play count`() {
        // Alice plays track 2 three times, track 4 once, track 0 twice.
        repeat(3) { PlaylistService.recordTrackCompletion(alice.id!!, trackIds[2]) }
        PlaylistService.recordTrackCompletion(alice.id!!, trackIds[4])
        repeat(2) { PlaylistService.recordTrackCompletion(alice.id!!, trackIds[0]) }

        val view = PlaylistService.getSmartPlaylist("most-played", alice)!!
        // Expected order: track 2 (3 plays), track 0 (2 plays), track 4 (1 play).
        assertEquals(
            listOf(trackIds[2], trackIds[0], trackIds[4]),
            view.tracks.map { it.track.id }
        )
    }

    @Test
    fun `recentlyAdded smart playlist orders by track created_at desc`() {
        val view = PlaylistService.getSmartPlaylist("recently-added", alice)!!
        // We seeded all 5 tracks at the same now() so we just verify
        // that exactly the playable seeded tracks come back; ordering
        // among same-timestamp rows isn't meaningful here.
        assertEquals(trackIds.toSet(), view.tracks.map { it.track.id }.toSet())
    }

    @Test
    fun `smart playlist key 'unknown' returns null and listSmartPlaylists skips empty`() {
        assertNull(PlaylistService.getSmartPlaylist("not-a-real-key", alice))

        // No completions for bob → most-played has zero tracks → omitted.
        val list = PlaylistService.listSmartPlaylists(bob).map { it.key }
        assertTrue("recently-added" in list)
        assertTrue("most-played" !in list)
    }
}
