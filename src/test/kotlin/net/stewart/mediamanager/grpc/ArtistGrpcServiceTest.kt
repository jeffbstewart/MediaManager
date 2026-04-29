package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.RecommendedArtist
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ArtistGrpcService] — listArtists / artist detail
 * (without the MusicBrainz "other works" path which needs an HTTP fake),
 * listAuthors / author detail, and the recommendation feed.
 */
class ArtistGrpcServiceTest : GrpcTestBase() {

    @Before
    fun cleanArtistTables() {
        // GrpcTestBase.cleanAllTables doesn't know about artist / author /
        // recommendation rows; clean them here so each test starts empty.
        RecommendedArtist.deleteAll()
        TitleArtist.deleteAll()
        TitleAuthor.deleteAll()
        Artist.deleteAll()
        Author.deleteAll()
    }

    // ---------------------- listArtists ----------------------

    @Test
    fun `listArtists returns an empty list when nothing is seeded`() = runBlocking {
        val viewer = createViewerUser(username = "artists-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listArtists(listArtistsRequest { })
            assertEquals(0, resp.artistsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listArtists surfaces seeded artists with their owned-album counts`() = runBlocking {
        val viewer = createViewerUser(username = "artists-list")
        val doors = Artist(name = "The Doors", sort_name = "Doors, The",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val miles = Artist(name = "Miles Davis", sort_name = "Davis, Miles",
            artist_type = ArtistType.PERSON.name).apply { save() }
        // Doors has 2 albums; Miles has 1.
        val a1 = createTitle(name = "L.A. Woman", mediaType = MediaTypeEntity.ALBUM.name)
        val a2 = createTitle(name = "Strange Days", mediaType = MediaTypeEntity.ALBUM.name)
        val a3 = createTitle(name = "Kind of Blue", mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = a1.id!!, artist_id = doors.id!!, artist_order = 0).save()
        TitleArtist(title_id = a2.id!!, artist_id = doors.id!!, artist_order = 0).save()
        TitleArtist(title_id = a3.id!!, artist_id = miles.id!!, artist_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listArtists(listArtistsRequest { })
            assertEquals(2, resp.artistsCount)
            val names = resp.artistsList.map { it.name }.toSet()
            assertTrue("The Doors" in names)
            assertTrue("Miles Davis" in names)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listArtists with q filter narrows by name or sort_name`() = runBlocking {
        val viewer = createViewerUser(username = "artists-q")
        Artist(name = "The Beatles", sort_name = "Beatles, The",
            artist_type = ArtistType.GROUP.name).apply { save() }
        Artist(name = "Stevie Wonder", sort_name = "Wonder, Stevie",
            artist_type = ArtistType.PERSON.name).apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listArtists(listArtistsRequest { q = "wonder" })
            assertEquals(1, resp.artistsCount)
            assertEquals("Stevie Wonder", resp.artistsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getArtistDetail ----------------------

    @Test
    fun `getArtistDetail returns NOT_FOUND for an unknown id`() = runBlocking {
        val viewer = createViewerUser(username = "artist-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getArtistDetail(artistIdRequest { artistId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getArtistDetail returns owned albums sorted by year and name`() = runBlocking {
        val viewer = createViewerUser(username = "artist-owned")
        val artist = Artist(name = "Bowie", sort_name = "Bowie",
            artist_type = ArtistType.PERSON.name,
            // No MBID -> buildArtistOtherWorks is short-circuited (no MB call).
        ).apply { save() }

        // Three albums in deliberately scrambled order.
        val ziggy = createTitle(name = "Ziggy Stardust",
            mediaType = MediaTypeEntity.ALBUM.name)
            .apply { release_year = 1972; save() }
        val low = createTitle(name = "Low",
            mediaType = MediaTypeEntity.ALBUM.name)
            .apply { release_year = 1977; save() }
        val heroes = createTitle(name = "Heroes",
            mediaType = MediaTypeEntity.ALBUM.name)
            .apply { release_year = 1977; save() }
        TitleArtist(title_id = low.id!!, artist_id = artist.id!!, artist_order = 0).save()
        TitleArtist(title_id = ziggy.id!!, artist_id = artist.id!!, artist_order = 0).save()
        TitleArtist(title_id = heroes.id!!, artist_id = artist.id!!, artist_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.getArtistDetail(artistIdRequest { artistId = artist.id!! })
            assertEquals("Bowie", resp.artist.name)
            assertEquals(0, resp.otherWorksCount, "no MBID -> empty discography")
            // Sort: by year ascending, then name (lowercase) ascending.
            val names = resp.ownedAlbumsList.map { it.name }
            assertEquals(listOf("Ziggy Stardust", "Heroes", "Low"), names)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listAuthors / getAuthorDetail ----------------------

    @Test
    fun `listAuthors empty when nothing is seeded`() = runBlocking {
        val viewer = createViewerUser(username = "authors-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listAuthors(listAuthorsRequest { })
            assertEquals(0, resp.authorsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listAuthors returns seeded authors and supports q filter`() = runBlocking {
        val viewer = createViewerUser(username = "authors-list")
        Author(name = "Isaac Asimov", sort_name = "Asimov, Isaac").apply { save() }
        Author(name = "Ursula K. Le Guin", sort_name = "Le Guin, Ursula K.").apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val all = stub.listAuthors(listAuthorsRequest { })
            assertEquals(2, all.authorsCount)

            val filtered = stub.listAuthors(listAuthorsRequest { q = "asimov" })
            assertEquals(1, filtered.authorsCount)
            assertEquals("Isaac Asimov", filtered.authorsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getAuthorDetail returns NOT_FOUND for unknown id`() = runBlocking {
        val viewer = createViewerUser(username = "author-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getAuthorDetail(authorIdRequest { authorId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getAuthorDetail returns the author with linked book count`() = runBlocking {
        val viewer = createViewerUser(username = "author-detail")
        val asimov = Author(name = "Isaac Asimov", sort_name = "Asimov, Isaac")
            // No OL id -> won't try to fetch from OpenLibrary.
            .apply { save() }
        val foundation = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name)
        TitleAuthor(title_id = foundation.id!!, author_id = asimov.id!!,
            author_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.getAuthorDetail(authorIdRequest { authorId = asimov.id!! })
            assertEquals("Isaac Asimov", resp.author.name)
            assertEquals(1, resp.ownedBooksCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- recommendations ----------------------

    @Test
    fun `listArtistRecommendations is empty when none seeded`() = runBlocking {
        val viewer = createViewerUser(username = "rec-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listArtistRecommendations(listArtistRecommendationsRequest { })
            assertEquals(0, resp.artistsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listArtistRecommendations returns active rows sorted by score and excludes dismissed`() = runBlocking {
        val viewer = createViewerUser(username = "rec-list")
        // Two active recommendations + one dismissed (excluded).
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            suggested_artist_name = "Top Match", score = 9.5,
            created_at = LocalDateTime.now()).save()
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            suggested_artist_name = "Second Match", score = 7.0,
            created_at = LocalDateTime.now()).save()
        RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            suggested_artist_name = "Dismissed", score = 8.0,
            created_at = LocalDateTime.now(),
            dismissed_at = LocalDateTime.now()).save()
        // Other user's recommendation must not surface.
        val other = createViewerUser(username = "rec-other")
        RecommendedArtist(user_id = other.id!!,
            suggested_artist_mbid = "dddddddd-dddd-dddd-dddd-dddddddddddd",
            suggested_artist_name = "Other User",
            score = 10.0, created_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val resp = stub.listArtistRecommendations(listArtistRecommendationsRequest { })
            assertEquals(2, resp.artistsCount,
                "dismissed and other-user rows excluded")
            // Score-desc order.
            assertEquals(listOf("Top Match", "Second Match"),
                resp.artistsList.map { it.suggestedArtistName })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissArtistRecommendation flips the dismissed_at timestamp`() = runBlocking {
        val viewer = createViewerUser(username = "rec-dismiss")
        val rec = RecommendedArtist(user_id = viewer.id!!,
            suggested_artist_mbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            suggested_artist_name = "To Dismiss", score = 5.0,
            created_at = LocalDateTime.now()).apply { save() }
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            stub.dismissArtistRecommendation(dismissArtistRecommendationRequest {
                suggestedArtistMbid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            })
            val refreshed = RecommendedArtist.findById(rec.id!!)!!
            assertTrue(refreshed.dismissed_at != null,
                "dismissed_at must be set after dismissArtistRecommendation")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissArtistRecommendation rejects a blank mbid with INVALID_ARGUMENT`() = runBlocking {
        val viewer = createViewerUser(username = "rec-blank")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.dismissArtistRecommendation(dismissArtistRecommendationRequest {
                    suggestedArtistMbid = ""
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissArtistRecommendation returns NOT_FOUND for an unknown mbid`() = runBlocking {
        val viewer = createViewerUser(username = "rec-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = ArtistServiceGrpcKt.ArtistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.dismissArtistRecommendation(dismissArtistRecommendationRequest {
                    suggestedArtistMbid = "ffffffff-ffff-ffff-ffff-ffffffffffff"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }
}
