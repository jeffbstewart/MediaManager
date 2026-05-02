package net.stewart.mediamanager.grpc

import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.TmdbCollection
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Coverage for [ImageGrpcService] — the bidi-streaming image RPC and the
 * resolve* dispatch tree. Drives every type of [ImageRef] through the
 * stream stub on the in-process server, asserting the response oneof
 * field for the entity / config combos the production code branches on.
 *
 * All disk-cache and CDN paths (PosterCache, BackdropCache, TMDB CDN,
 * Cover Art Archive proxy, OpenLibrary proxy, go2rtc snapshot) are
 * deliberately *not* exercised — they require real HTTP / file I/O the
 * unit test wouldn't reproduce faithfully. The test focuses on the
 * not_found / not_modified / permission_denied gates that bracket those
 * fetches, which is the bulk of the dispatch logic.
 */
internal class ImageGrpcServiceTest : GrpcTestBase() {

    @After
    fun cleanImageEntities() {
        // Tables this test populates that GrpcTestBase doesn't touch.
        Camera.deleteAll()
        Artist.deleteAll()
        Author.deleteAll()
        TmdbCollection.deleteAll()
    }

    /** Drive a finite list of requests through streamImages and collect the responses. */
    private fun roundTrip(
        user: AppUser,
        requests: List<ImageRequest>,
    ): List<ImageResponse> = runBlocking {
        val channel = authenticatedChannel(user)
        try {
            val stub = ImageServiceGrpcKt.ImageServiceCoroutineStub(channel)
            // Take exactly `requests.size` responses — the server emits
            // one per `fetch` (cancelStale doesn't produce a response).
            val expected = requests.count { it.hasFetch() }
            stub.streamImages(flowOf(*requests.toTypedArray()))
                .take(expected)
                .toList()
        } finally {
            channel.shutdownNow()
        }
    }

    /** One-shot helper: fetch a single ref as the given user and return the response. */
    private fun fetchOnce(user: AppUser, ref: ImageRef, ifNoneMatch: String? = null): ImageResponse {
        val req = imageRequest {
            fetch = fetchImage {
                requestId = 1
                this.ref = ref
                if (ifNoneMatch != null) this.ifNoneMatch = ifNoneMatch
            }
        }
        return roundTrip(user, listOf(req)).single()
    }

    // ---------------------- admin gate (OWNERSHIP_PHOTO) ----------------------

    @Test
    fun `OWNERSHIP_PHOTO returns permission_denied for non-admin viewers`() {
        val viewer = createViewerUser()
        val resp = fetchOnce(viewer, imageRef {
            type = ImageType.IMAGE_TYPE_OWNERSHIP_PHOTO
            uuid = "any-uuid"
        })
        assertTrue(resp.hasPermissionDenied(), "viewer should be denied OWNERSHIP_PHOTO")
    }

    @Test
    fun `OWNERSHIP_PHOTO with blank UUID returns not_found for an admin`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_OWNERSHIP_PHOTO
            uuid = ""
        })
        assertTrue(resp.hasNotFound(), "blank UUID is short-circuited to not_found")
    }

    @Test
    fun `OWNERSHIP_PHOTO with a valid-looking UUID but no file returns not_found`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_OWNERSHIP_PHOTO
            uuid = java.util.UUID.randomUUID().toString()
        })
        assertTrue(resp.hasNotFound())
    }

    // ---------------------- type dispatch ----------------------

    @Test
    fun `IMAGE_TYPE_UNKNOWN falls through the dispatch and returns not_found`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef { type = ImageType.IMAGE_TYPE_UNKNOWN })
        assertTrue(resp.hasNotFound())
    }

    // ---------------------- POSTER ----------------------

    @Test
    fun `POSTER_THUMBNAIL returns not_found when title id does not exist`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
            titleId = 99999L
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `POSTER_THUMBNAIL returns not_found when title has no poster_path`() {
        val admin = createAdminUser()
        val title = createTitle(name = "Posterless", posterPath = null)
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
            titleId = title.id!!
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `POSTER_FULL returns not_modified when ifNoneMatch matches the etag`() {
        val admin = createAdminUser()
        val title = createTitle(posterPath = "/p.jpg")
        // ETag for posters is poster_cache_id ?: poster_path; we set
        // posterPath only, so etag == "/p.jpg".
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_POSTER_FULL
            titleId = title.id!!
        }, ifNoneMatch = "/p.jpg")
        assertTrue(resp.hasNotModified())
    }

    @Test
    fun `POSTER returns not_found when the user's rating ceiling rejects the title`() {
        // PG-13 ceiling = ordinal 4; R-rated title = ordinal 5 → blocked.
        val viewer = createViewerUser().apply {
            rating_ceiling = 4
            save()
        }
        val title = createTitle(name = "Hard R", contentRating = "R", posterPath = "/r.jpg")
        val resp = fetchOnce(viewer, imageRef {
            type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
            titleId = title.id!!
        })
        assertTrue(resp.hasNotFound(), "rating ceiling gate should yield not_found")
    }

    // ---------------------- BACKDROP ----------------------

    @Test
    fun `BACKDROP returns not_found when title is missing`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_BACKDROP
            titleId = 12345L
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `BACKDROP returns not_found when title has no backdrop_path`() {
        val admin = createAdminUser()
        val title = createTitle(posterPath = "/p.jpg")
        // backdrop_path is left null by createTitle helper.
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_BACKDROP
            titleId = title.id!!
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `BACKDROP returns not_modified when ifNoneMatch matches the etag`() {
        val admin = createAdminUser()
        val title = createTitle().apply {
            backdrop_path = "/b.jpg"
            save()
        }
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_BACKDROP
            titleId = title.id!!
        }, ifNoneMatch = "/b.jpg")
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- HEADSHOT ----------------------

    @Test
    fun `HEADSHOT returns not_found when no CastMember has the tmdb_person_id`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_HEADSHOT
            tmdbPersonId = 999
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `HEADSHOT returns not_found when CastMember has no profile_path`() {
        val admin = createAdminUser()
        val title = createTitle()
        CastMember(title_id = title.id!!, name = "Actor",
            tmdb_person_id = 555, profile_path = null).save()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_HEADSHOT
            tmdbPersonId = 555
        })
        assertTrue(resp.hasNotFound())
    }

    // ---------------------- COLLECTION_POSTER ----------------------

    @Test
    fun `COLLECTION_POSTER returns not_found when collection id is unknown`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_COLLECTION_POSTER
            tmdbCollectionId = 88
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `COLLECTION_POSTER returns not_found when collection has no poster_path`() {
        val admin = createAdminUser()
        TmdbCollection(tmdb_collection_id = 88, name = "X",
            poster_path = null).save()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_COLLECTION_POSTER
            tmdbCollectionId = 88
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `COLLECTION_POSTER returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        TmdbCollection(tmdb_collection_id = 42, name = "Saga",
            poster_path = "/c.jpg").save()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_COLLECTION_POSTER
            tmdbCollectionId = 42
        }, ifNoneMatch = "collection-42-/c.jpg")
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- LOCAL_IMAGE ----------------------

    @Test
    fun `LOCAL_IMAGE returns not_found for blank UUID`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_LOCAL_IMAGE
            uuid = ""
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `LOCAL_IMAGE returns not_modified when ifNoneMatch matches the prefixed etag`() {
        val admin = createAdminUser()
        val theUuid = java.util.UUID.randomUUID().toString()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_LOCAL_IMAGE
            uuid = theUuid
        }, ifNoneMatch = "local-$theUuid")
        assertTrue(resp.hasNotModified(), "ETag short-circuits before the file lookup")
    }

    // ---------------------- TMDB_POSTER ----------------------

    @Test
    fun `TMDB_POSTER returns not_found when tmdb_id is zero`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_TMDB_POSTER
            tmdbMedia = tmdbMediaId {
                tmdbId = 0
                mediaType = net.stewart.mediamanager.grpc.MediaType.MEDIA_TYPE_MOVIE
            }
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `TMDB_POSTER returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        // ETag is "tmdb-${number}-${tmdbId}" — short-circuits before
        // the actual TMDB poster_path lookup, so we don't need any
        // local data.
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_TMDB_POSTER
            tmdbMedia = tmdbMediaId {
                tmdbId = 7
                mediaType = net.stewart.mediamanager.grpc.MediaType.MEDIA_TYPE_TV
            }
        }, ifNoneMatch = "tmdb-${net.stewart.mediamanager.grpc.MediaType.MEDIA_TYPE_TV.number}-7")
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- ARTIST_HEADSHOT ----------------------

    @Test
    fun `ARTIST_HEADSHOT returns not_found when artist_id is non-positive`() {
        val admin = createAdminUser()
        for (id in listOf(0L, -1L)) {
            val resp = fetchOnce(admin, imageRef {
                type = ImageType.IMAGE_TYPE_ARTIST_HEADSHOT
                artistId = id
            })
            assertTrue(resp.hasNotFound(), "artist id $id should be rejected")
        }
    }

    @Test
    fun `ARTIST_HEADSHOT returns not_found when artist row is missing`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_ARTIST_HEADSHOT
            artistId = 9999L
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `ARTIST_HEADSHOT returns not_found when artist has blank headshot_path`() {
        val admin = createAdminUser()
        val artist = Artist(name = "Test Artist", sort_name = "test artist",
            headshot_path = "  ").apply { save() }
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_ARTIST_HEADSHOT
            artistId = artist.id!!
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `ARTIST_HEADSHOT returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        val artist = Artist(name = "Test Artist", sort_name = "test artist",
            headshot_path = "/h.jpg").apply { save() }
        val expectedEtag = "artist-${artist.id}-${"/h.jpg".hashCode()}"
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_ARTIST_HEADSHOT
            artistId = artist.id!!
        }, ifNoneMatch = expectedEtag)
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- AUTHOR_HEADSHOT ----------------------

    @Test
    fun `AUTHOR_HEADSHOT mirrors the artist branches — rejects non-positive id`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_AUTHOR_HEADSHOT
            authorId = 0L
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `AUTHOR_HEADSHOT returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        val author = Author(name = "Test Author", sort_name = "test author",
            headshot_path = "/a.jpg").apply { save() }
        val expectedEtag = "author-${author.id}-${"/a.jpg".hashCode()}"
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_AUTHOR_HEADSHOT
            authorId = author.id!!
        }, ifNoneMatch = expectedEtag)
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- CAA_RELEASE_GROUP ----------------------

    @Test
    fun `CAA_RELEASE_GROUP returns not_found for blank or malformed MBID`() {
        val admin = createAdminUser()
        for (mbid in listOf("", "not-a-uuid", "ZZZZZZZZ-1111-2222-3333-444444444444")) {
            val resp = fetchOnce(admin, imageRef {
                type = ImageType.IMAGE_TYPE_CAA_RELEASE_GROUP
                musicbrainzReleaseGroupId = mbid
            })
            assertTrue(resp.hasNotFound(), "expected not_found for mbid='$mbid'")
        }
    }

    @Test
    fun `CAA_RELEASE_GROUP returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        val mbid = java.util.UUID.randomUUID().toString()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_CAA_RELEASE_GROUP
            musicbrainzReleaseGroupId = mbid
        }, ifNoneMatch = "caa-rg-$mbid")
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- OPENLIBRARY_COVER ----------------------

    @Test
    fun `OPENLIBRARY_COVER returns not_found for blank or malformed work id`() {
        val admin = createAdminUser()
        for (id in listOf("", "garbage", "OL-not-an-id")) {
            val resp = fetchOnce(admin, imageRef {
                type = ImageType.IMAGE_TYPE_OPENLIBRARY_COVER
                openlibraryWorkId = id
            })
            assertTrue(resp.hasNotFound(), "expected not_found for ol id='$id'")
        }
    }

    @Test
    fun `OPENLIBRARY_COVER returns not_modified when ifNoneMatch matches the synthetic etag`() {
        val admin = createAdminUser()
        val id = "OL12345W"
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_OPENLIBRARY_COVER
            openlibraryWorkId = id
        }, ifNoneMatch = "ol-work-$id")
        assertTrue(resp.hasNotModified())
    }

    // ---------------------- CAMERA_SNAPSHOT ----------------------

    @Test
    fun `CAMERA_SNAPSHOT returns not_found when camera id is unknown`() {
        val admin = createAdminUser()
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_CAMERA_SNAPSHOT
            cameraId = 9999L
        })
        assertTrue(resp.hasNotFound())
    }

    @Test
    fun `CAMERA_SNAPSHOT returns not_found when camera is disabled`() {
        val admin = createAdminUser()
        val cam = Camera(go2rtc_name = "front-door", enabled = false).apply { save() }
        val resp = fetchOnce(admin, imageRef {
            type = ImageType.IMAGE_TYPE_CAMERA_SNAPSHOT
            cameraId = cam.id!!
        })
        assertTrue(resp.hasNotFound())
    }

    // ---------------------- cancel-stale watermark ----------------------

    @Test
    fun `cancelStale watermark suppresses fetches with request_id at or below the watermark`() = runBlocking {
        val admin = createAdminUser()
        val title = createTitle(name = "Posterless", posterPath = null)

        val refMissingPoster = imageRef {
            type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
            titleId = title.id!!
        }

        // Sequence:
        //   1. fetch id=1 → emits not_found (id 1 > -1 watermark)
        //   2. cancelStale watermark=10 (no response)
        //   3. fetch id=5  → suppressed (5 <= 10)
        //   4. fetch id=11 → emits not_found
        // Expected: exactly two responses, request_ids 1 and 11.
        val requests = listOf(
            imageRequest {
                fetch = fetchImage { requestId = 1; ref = refMissingPoster }
            },
            imageRequest { cancelStale = cancelStale { beforeRequestId = 10 } },
            imageRequest {
                fetch = fetchImage { requestId = 5; ref = refMissingPoster }
            },
            imageRequest {
                fetch = fetchImage { requestId = 11; ref = refMissingPoster }
            },
        )

        val channel = authenticatedChannel(admin)
        try {
            val stub = ImageServiceGrpcKt.ImageServiceCoroutineStub(channel)
            val responses = stub.streamImages(flowOf(*requests.toTypedArray()))
                .take(2)  // only id=1 and id=11 should produce responses
                .toList()
            assertEquals(2, responses.size)
            assertEquals(listOf(1, 11), responses.map { it.requestId })
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- unauthenticated channel ----------------------

    @Test
    fun `streamImages without an auth token is rejected by the AuthInterceptor`() {
        // No token → the AuthInterceptor closes the call before
        // reaching the service. The flow collection sees an
        // exception when it terminates.
        val anonymousChannel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor()
            .build()
        try {
            runBlocking {
                val stub = ImageServiceGrpcKt.ImageServiceCoroutineStub(anonymousChannel)
                val req = imageRequest {
                    fetch = fetchImage {
                        requestId = 1
                        ref = imageRef {
                            type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
                            titleId = 1L
                        }
                    }
                }
                try {
                    stub.streamImages(flowOf(req)).toList()
                    fail("expected an auth-related exception")
                } catch (e: StatusException) {
                    // Expected — AuthInterceptor rejects the call.
                }
            }
        } finally {
            anonymousChannel.shutdownNow()
        }
    }
}
