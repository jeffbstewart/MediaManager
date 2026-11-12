package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.RecommendedArtist
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [RecommendationGrpcService] — list / dismiss /
 * refresh of M8 library recommendations. The refresh path fires off a
 * background thread that hits the recommendation agent; the sync part
 * we verify is just that the RPC returns refreshStarted = true.
 */
class RecommendationGrpcServiceTest : GrpcTestBase() {

    @Before
    fun cleanRecommendationTables() {
        RecommendedArtist.deleteAll()
        Artist.deleteAll()
    }

    // ---------------------- listRecommendedArtists ----------------------

    @Test
    fun `listRecommendedArtists returns empty when none seeded`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val resp = stub.listRecommendedArtists(listRecommendedArtistsRequest { })
            assertEquals(0, resp.artistsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listRecommendedArtists returns active rows sorted by score and excludes dismissed and other users`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-list")
        // Two active for viewer with different scores.
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            suggested_artist_name = "High Score", score = 9.5,
            created_at = LocalDateTime.now()).save()
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            suggested_artist_name = "Mid Score", score = 5.0,
            created_at = LocalDateTime.now()).save()
        // Dismissed — filtered out.
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            suggested_artist_name = "Dismissed", score = 8.0,
            created_at = LocalDateTime.now(),
            dismissed_at = LocalDateTime.now()).save()
        // Other user — filtered out.
        val other = createViewerUser(username = "rec-grpc-other")
        RecommendedArtist(user_id = other.id!!,
            suggested_artist_mbid = "dddddddd-dddd-dddd-dddd-dddddddddddd",
            suggested_artist_name = "Other User", score = 10.0,
            created_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val resp = stub.listRecommendedArtists(listRecommendedArtistsRequest { })
            assertEquals(2, resp.artistsCount)
            assertEquals(listOf("High Score", "Mid Score"),
                resp.artistsList.map { it.suggestedArtistName })
            // last_refreshed should be populated when there are rows.
            assertTrue(resp.hasLastRefreshed())
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listRecommendedArtists populates suggested_artist_id when an Artist row exists for the mbid`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-artistid")
        val mbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val artist = Artist(name = "Resolved",
            sort_name = "Resolved",
            artist_type = ArtistType.PERSON.name,
            musicbrainz_artist_id = mbid).apply { save() }
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = mbid,
            suggested_artist_name = "Resolved", score = 1.0,
            created_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val resp = stub.listRecommendedArtists(listRecommendedArtistsRequest { })
            assertEquals(1, resp.artistsCount)
            // suggested_artist_id resolved from the matching MBID.
            assertEquals(artist.id!!, resp.artistsList.single().suggestedArtistId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listRecommendedArtists honors limit clamp`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-limit")
        // Seed 5 active recommendations with descending scores.
        listOf(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" to 5.0,
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" to 4.0,
            "cccccccc-cccc-cccc-cccc-cccccccccccc" to 3.0,
            "dddddddd-dddd-dddd-dddd-dddddddddddd" to 2.0,
            "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee" to 1.0,
        ).forEach { (m, s) ->
            RecommendedArtist(user_id = viewer.id!!,
                suggested_artist_mbid = m,
                suggested_artist_name = "Artist@$s",
                score = s, created_at = LocalDateTime.now()).save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val limited = stub.listRecommendedArtists(listRecommendedArtistsRequest {
                limit = 2
            })
            assertEquals(2, limited.artistsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- dismissRecommendation ----------------------

    @Test
    fun `dismissRecommendation flips the dismissed_at timestamp`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-dismiss")
        val rec = RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            suggested_artist_name = "ToDismiss",
            score = 5.0, created_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            stub.dismissRecommendation(dismissRecommendationRequest {
                suggestedArtistMbid = rec.suggested_artist_mbid
            })
            val refreshed = RecommendedArtist.findById(rec.id!!)!!
            assertTrue(refreshed.dismissed_at != null,
                "dismissed_at must be set after dismissRecommendation")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissRecommendation rejects blank mbid with INVALID_ARGUMENT`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-blank")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.dismissRecommendation(dismissRecommendationRequest {
                    suggestedArtistMbid = ""
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissRecommendation returns NOT_FOUND for an unknown mbid`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.dismissRecommendation(dismissRecommendationRequest {
                    suggestedArtistMbid = "ffffffff-ffff-ffff-ffff-ffffffffffff"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- refreshRecommendations ----------------------

    @Test
    fun `refreshRecommendations returns refreshStarted true and fires off a daemon`() = runBlocking {
        val viewer = createViewerUser(username = "rec-grpc-refresh")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = RecommendationServiceGrpcKt.RecommendationServiceCoroutineStub(authed)
            val resp = stub.refreshRecommendations(Empty.getDefaultInstance())
            // Synchronous half of the contract — the agent invocation is
            // fire-and-forget on a daemon thread.
            assertTrue(resp.refreshStarted)
        } finally {
            authed.shutdownNow()
        }
    }
}
