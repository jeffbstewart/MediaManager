package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.service.WishListService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [WishListGrpcService] paths beyond what
 * [WishListLifecycleTest] covers — addWish / cancel / dismiss / vote,
 * the per-type add+remove flows for transcode / book / album wishes,
 * and the listTranscodeWishes status mapping.
 */
class WishListGrpcServiceTest : GrpcTestBase() {

    // ---------------------- addWish ----------------------

    @Test
    fun `addWish creates a movie wish then refuses a duplicate with ALREADY_EXISTS`() = runBlocking {
        val viewer = createViewerUser(username = "addwish-dup")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            val first = stub.addWish(addWishRequest {
                tmdbId = 555
                mediaType = MediaType.MEDIA_TYPE_MOVIE
                title = "First Add"
                releaseYear = 2024
            })
            assertTrue(first.id > 0)

            val ex = assertFailsWith<StatusException> {
                stub.addWish(addWishRequest {
                    tmdbId = 555
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                    title = "First Add"
                })
            }
            assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `addWish with TV media type and season_number creates a season-scoped wish`() = runBlocking {
        val viewer = createViewerUser(username = "addwish-season")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.addWish(addWishRequest {
                tmdbId = 700
                mediaType = MediaType.MEDIA_TYPE_TV
                title = "Some Show"
                seasonNumber = 3
            })
            val saved = WishListItem.findAll().single { it.user_id == viewer.id }
            assertEquals(MediaTypeEntity.TV.name, saved.tmdb_media_type)
            assertEquals(3, saved.season_number)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- cancelWish + dismissWish ----------------------

    @Test
    fun `cancelWish flips status for the owner and silently ignores non-owners`() = runBlocking {
        val owner = createViewerUser(username = "cancel-owner")
        val intruder = createViewerUser(username = "cancel-intruder")
        val wish = WishListService.addMediaWishForUser(owner.id!!,
            TmdbId(101, MediaTypeEntity.MOVIE), "Mine", null, null, null)!!

        val intruderChannel = authenticatedChannel(intruder)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(intruderChannel)
            // Intruder calling cancelWish on the owner's wish — silent no-op.
            stub.cancelWish(wishIdRequest { wishId = wish.id!! })
            assertEquals(WishStatus.ACTIVE.name,
                WishListItem.findById(wish.id!!)!!.status)
        } finally {
            intruderChannel.shutdownNow()
        }

        val ownerChannel = authenticatedChannel(owner)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(ownerChannel)
            stub.cancelWish(wishIdRequest { wishId = wish.id!! })
            assertEquals(WishStatus.CANCELLED.name,
                WishListItem.findById(wish.id!!)!!.status)
        } finally {
            ownerChannel.shutdownNow()
        }
    }

    @Test
    fun `dismissWish flips status to DISMISSED for the owner only`() = runBlocking {
        val owner = createViewerUser(username = "dismiss-owner")
        val intruder = createViewerUser(username = "dismiss-intruder")
        val wish = WishListService.addMediaWishForUser(owner.id!!,
            TmdbId(202, MediaTypeEntity.MOVIE), "Theirs", null, null, null)!!

        val intruderChannel = authenticatedChannel(intruder)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(intruderChannel)
            stub.dismissWish(wishIdRequest { wishId = wish.id!! })
            assertEquals(WishStatus.ACTIVE.name,
                WishListItem.findById(wish.id!!)!!.status)
        } finally {
            intruderChannel.shutdownNow()
        }

        val ownerChannel = authenticatedChannel(owner)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(ownerChannel)
            stub.dismissWish(wishIdRequest { wishId = wish.id!! })
            assertEquals(WishStatus.DISMISSED.name,
                WishListItem.findById(wish.id!!)!!.status)
        } finally {
            ownerChannel.shutdownNow()
        }
    }

    @Test
    fun `cancelWish on unknown id is a silent no-op`() = runBlocking {
        val viewer = createViewerUser(username = "cancel-unknown")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            // Should not throw.
            stub.cancelWish(wishIdRequest { wishId = 999_999 })
            assertEquals(0, WishListItem.findAll().size)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- voteOnWish ----------------------

    @Test
    fun `voteOnWish vote=true creates an ACTIVE row for the caller`() = runBlocking {
        val author = createViewerUser(username = "vote-author")
        val voter = createViewerUser(username = "vote-yes")
        val wish = WishListService.addMediaWishForUser(author.id!!,
            TmdbId(303, MediaTypeEntity.MOVIE), "Voted On", null, null, null)!!
        // Voter has no row for this wish yet.
        assertEquals(0, WishListItem.findAll().count { it.user_id == voter.id })

        val authedChannel = authenticatedChannel(voter)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.voteOnWish(voteRequest { wishId = wish.id!!; vote = true })

            val voterRow = WishListItem.findAll().single { it.user_id == voter.id }
            assertEquals(303, voterRow.tmdb_id)
            assertEquals(WishStatus.ACTIVE.name, voterRow.status)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `voteOnWish vote=true is idempotent — no second row when one already ACTIVE`() = runBlocking {
        val voter = createViewerUser(username = "vote-idem")
        val wish = WishListService.addMediaWishForUser(voter.id!!,
            TmdbId(404, MediaTypeEntity.MOVIE), "Already Voted", null, null, null)!!
        val authedChannel = authenticatedChannel(voter)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.voteOnWish(voteRequest { wishId = wish.id!!; vote = true })
            assertEquals(1, WishListItem.findAll().count { it.user_id == voter.id })
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `voteOnWish vote=false cancels the caller's existing ACTIVE wish`() = runBlocking {
        val voter = createViewerUser(username = "vote-no")
        val wish = WishListService.addMediaWishForUser(voter.id!!,
            TmdbId(505, MediaTypeEntity.MOVIE), "Reverted", null, null, null)!!
        val authedChannel = authenticatedChannel(voter)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.voteOnWish(voteRequest { wishId = wish.id!!; vote = false })
            assertEquals(WishStatus.CANCELLED.name,
                WishListItem.findById(wish.id!!)!!.status)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `voteOnWish on unknown wish id is a silent no-op`() = runBlocking {
        val voter = createViewerUser(username = "vote-unknown")
        val authedChannel = authenticatedChannel(voter)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.voteOnWish(voteRequest { wishId = 999_999; vote = true })
            assertEquals(0, WishListItem.findAll().size)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- transcode wishes ----------------------

    @Test
    fun `addTranscodeWish refuses duplicates with ALREADY_EXISTS`() = runBlocking {
        val viewer = createViewerUser(username = "transcode-dup")
        val title = createTitle(name = "T")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.addTranscodeWish(titleIdRequest { titleId = title.id!! })
            val ex = assertFailsWith<StatusException> {
                stub.addTranscodeWish(titleIdRequest { titleId = title.id!! })
            }
            assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `removeTranscodeWish silently no-ops when nothing matches`() = runBlocking {
        val viewer = createViewerUser(username = "transcode-rm")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            // No active wish to remove — should not throw.
            stub.removeTranscodeWish(titleIdRequest { titleId = 99_999 })
            assertEquals(0, WishListItem.findAll().size)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listTranscodeWishes returns the caller's active wishes with PENDING status when no transcode exists`() = runBlocking {
        val viewer = createViewerUser(username = "transcode-list")
        val title = createTitle(name = "Pending Title")

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.addTranscodeWish(titleIdRequest { titleId = title.id!! })
            val resp = stub.listTranscodeWishes(Empty.getDefaultInstance())
            assertEquals(1, resp.wishesCount)
            val item = resp.wishesList.single()
            assertEquals(title.id, item.titleId)
            assertEquals("Pending Title", item.titleName)
            // No transcodes seeded -> PENDING (READY requires all transcodes done).
            assertEquals(TranscodeWishStatus.TRANSCODE_WISH_STATUS_PENDING, item.status)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- book wishes ----------------------

    @Test
    fun `addBookWish requires non-blank ol_work_id and title`() = runBlocking {
        val viewer = createViewerUser(username = "book-blank")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)

            val noWorkId = assertFailsWith<StatusException> {
                stub.addBookWish(addBookWishRequest { olWorkId = ""; title = "T" })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noWorkId.status.code)

            val noTitle = assertFailsWith<StatusException> {
                stub.addBookWish(addBookWishRequest { olWorkId = "OL1W"; title = "" })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noTitle.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `addBookWish creates a wish with optional fields populated`() = runBlocking {
        val viewer = createViewerUser(username = "book-add")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            val resp = stub.addBookWish(addBookWishRequest {
                olWorkId = "OL46125W"
                title = "Foundation"
                author = "Isaac Asimov"
                coverIsbn = "0553293354"
                seriesId = 42
                seriesNumber = "1"
            })
            assertTrue(resp.id > 0)
            val saved = WishListItem.findById(resp.id)!!
            assertEquals("OL46125W", saved.open_library_work_id)
            assertEquals("Foundation", saved.book_title)
            assertEquals("Isaac Asimov", saved.book_author)
            assertEquals("0553293354", saved.book_cover_isbn)
            assertEquals(42L, saved.book_series_id)
            // BigDecimal scale may be 2 (H2 DECIMAL(p,2)); compare numerically.
            assertEquals(0, java.math.BigDecimal("1").compareTo(saved.book_series_number),
                "book_series_number numerically equals 1")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `removeBookWish silently no-ops on unknown work`() = runBlocking {
        val viewer = createViewerUser(username = "book-rm")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.removeBookWish(removeBookWishRequest { olWorkId = "OL-NONE" })
            assertEquals(0, WishListItem.findAll().size)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- album wishes ----------------------

    @Test
    fun `addAlbumWish requires non-blank release_group_id and title`() = runBlocking {
        val viewer = createViewerUser(username = "album-blank")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)

            val noRg = assertFailsWith<StatusException> {
                stub.addAlbumWish(addAlbumWishRequest { releaseGroupId = ""; title = "T" })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noRg.status.code)

            val noTitle = assertFailsWith<StatusException> {
                stub.addAlbumWish(addAlbumWishRequest { releaseGroupId = "rg-1"; title = "" })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noTitle.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `addAlbumWish creates a wish with optional fields populated`() = runBlocking {
        val viewer = createViewerUser(username = "album-add")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            val resp = stub.addAlbumWish(addAlbumWishRequest {
                releaseGroupId = "rg-1"
                title = "Album"
                primaryArtist = "Artist"
                year = 2010
                coverReleaseId = "cov-1"
                isCompilation = false
            })
            assertTrue(resp.id > 0)
            val saved = WishListItem.findById(resp.id)!!
            assertEquals("rg-1", saved.musicbrainz_release_group_id)
            assertEquals("Album", saved.album_title)
            assertEquals("Artist", saved.album_primary_artist)
            assertEquals(2010, saved.album_year)
            assertEquals("cov-1", saved.album_cover_release_id)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `removeAlbumWish silently no-ops on unknown release group`() = runBlocking {
        val viewer = createViewerUser(username = "album-rm")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            stub.removeAlbumWish(removeAlbumWishRequest { releaseGroupId = "rg-NONE" })
            assertEquals(0, WishListItem.findAll().size)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
