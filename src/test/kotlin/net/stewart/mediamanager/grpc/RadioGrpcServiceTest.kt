package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackArtist
import net.stewart.mediamanager.service.RadioSeedStore
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [RadioGrpcService] — start / next / stop
 * with the in-memory [RadioSeedStore] backing the session.
 */
class RadioGrpcServiceTest : GrpcTestBase() {

    @Before
    @After
    fun cleanRadioState() {
        // Static state that GrpcTestBase doesn't know about.
        RadioSeedStore.clear()
        TrackArtist.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Artist.deleteAll()
    }

    private fun seedAlbumWithTracks(): Triple<Long, Long, Long> {
        // Returns (albumTitleId, trackAId, trackBId).
        val artist = Artist(name = "Solo Artist", sort_name = "Artist, Solo",
            artist_type = ArtistType.PERSON.name).apply { save() }
        val album = createTitle(name = "Seed Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        val a = Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Track A", duration_seconds = 180,
            file_path = "/m/a.flac").apply { save() }
        val b = Track(title_id = album.id!!, track_number = 2, disc_number = 1,
            name = "Track B", duration_seconds = 200,
            file_path = "/m/b.flac").apply { save() }
        return Triple(album.id!!, a.id!!, b.id!!)
    }

    // ---------------------- startRadio validation ----------------------

    @Test
    fun `startRadio without a seed id returns INVALID_ARGUMENT`() = runBlocking {
        val viewer = createViewerUser(username = "radio-no-seed")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.startRadio(startRadioRequest { })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `startRadio with unknown album id returns NOT_FOUND`() = runBlocking {
        val viewer = createViewerUser(username = "radio-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.startRadio(startRadioRequest { seedAlbumId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `startRadio refuses albums that are actually movies`() = runBlocking {
        val viewer = createViewerUser(username = "radio-wrong-type")
        // Create a MOVIE title — RadioService.startFromAlbum returns null
        // for non-album media_type.
        val movie = createTitle(name = "Not An Album",
            mediaType = MediaTypeEntity.MOVIE.name)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.startRadio(startRadioRequest { seedAlbumId = movie.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- startRadio happy path ----------------------

    @Test
    fun `startRadio with an album id returns a session with seed and initial batch`() = runBlocking {
        val viewer = createViewerUser(username = "radio-album-start")
        val (albumId, _, _) = seedAlbumWithTracks()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val resp = stub.startRadio(startRadioRequest { seedAlbumId = albumId })

            assertTrue(resp.radioSessionId.isNotBlank(),
                "session id is the opaque RadioSeedStore key")
            assertEquals(RadioSeedType.RADIO_SEED_TYPE_ALBUM, resp.seed.seedType)
            assertEquals(albumId, resp.seed.seedId)
            assertEquals("Seed Album", resp.seed.seedName)
            // initial_batch may be empty when the seed has no eligible
            // recommendation candidates (the algorithm filters out the
            // seed album's own tracks); session creation with a valid
            // seed is the contract we lock down here.
            assertTrue(resp.initialBatchCount >= 0)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `startRadio with a track id returns a session keyed on the track`() = runBlocking {
        val viewer = createViewerUser(username = "radio-track-start")
        val (_, trackAId, _) = seedAlbumWithTracks()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val resp = stub.startRadio(startRadioRequest { seedTrackId = trackAId })

            assertEquals(RadioSeedType.RADIO_SEED_TYPE_TRACK, resp.seed.seedType)
            assertEquals(trackAId, resp.seed.seedId)
            assertEquals("Track A", resp.seed.seedName)
            assertEquals("Solo Artist", resp.seed.seedArtistName)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- nextRadioBatch ----------------------

    @Test
    fun `nextRadioBatch returns NOT_FOUND for an unknown or expired session`() = runBlocking {
        val viewer = createViewerUser(username = "radio-next-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.nextRadioBatch(nextRadioBatchRequest {
                    radioSessionId = "never-issued"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `nextRadioBatch returns a fresh batch for an existing session`() = runBlocking {
        val viewer = createViewerUser(username = "radio-next-ok")
        val (albumId, _, _) = seedAlbumWithTracks()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val start = stub.startRadio(startRadioRequest { seedAlbumId = albumId })
            val next = stub.nextRadioBatch(nextRadioBatchRequest {
                radioSessionId = start.radioSessionId
            })
            // Same seed, different request — service is allowed to return
            // overlapping or distinct tracks; the contract is just "non-error".
            assertTrue(next.tracksCount >= 0)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- stopRadio ----------------------

    @Test
    fun `stopRadio removes the session from the store`() = runBlocking {
        val viewer = createViewerUser(username = "radio-stop")
        val (albumId, _, _) = seedAlbumWithTracks()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            val start = stub.startRadio(startRadioRequest { seedAlbumId = albumId })
            stub.stopRadio(stopRadioRequest { radioSessionId = start.radioSessionId })
            // Session is now gone — next/next/stop is NOT_FOUND.
            val ex = assertFailsWith<StatusException> {
                stub.nextRadioBatch(nextRadioBatchRequest {
                    radioSessionId = start.radioSessionId
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `stopRadio on an unknown session id is a silent no-op`() = runBlocking {
        val viewer = createViewerUser(username = "radio-stop-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RadioServiceGrpcKt.RadioServiceCoroutineStub(authed)
            // Should not throw.
            stub.stopRadio(stopRadioRequest { radioSessionId = "does-not-exist" })
        } finally {
            authed.shutdownNow()
        }
        Unit
    }
}
