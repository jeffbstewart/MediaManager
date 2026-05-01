package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for the small/leaf RPCs on [CatalogGrpcService] —
 * setTitleTags / setTrackTags / listTagsForTrack / getGenreDetail /
 * getTagDetail (track branch) / requestRetranscode /
 * requestLowStorageTranscode.
 */
class CatalogGrpcServiceSmallRpcsTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        TrackTag.deleteAll()
        Track.deleteAll()
        TitleArtist.deleteAll()
        Artist.deleteAll()
    }

    private fun seedAlbumWithTrack(): Pair<Long, Long> {
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T").apply { save() }
        return album.id!! to track.id!!
    }

    // ---------------------- setTitleTags ----------------------

    @Test
    fun `setTitleTags requires admin and rejects unknown title id`() = runBlocking {
        val viewer = createViewerUser(username = "stt-viewer")
        val viewerChannel = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(viewerChannel)
            val ex = assertFailsWith<StatusException> {
                stub.setTitleTags(setTitleTagsRequest { titleId = 1 })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            viewerChannel.shutdownNow()
        }

        val admin = createAdminUser(username = "stt-admin-404")
        val adminChannel = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(adminChannel)
            val ex = assertFailsWith<StatusException> {
                stub.setTitleTags(setTitleTagsRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            adminChannel.shutdownNow()
        }
    }

    @Test
    fun `setTitleTags reconciles desired and existing tag ids`() = runBlocking {
        val admin = createAdminUser(username = "stt-reconcile")
        val title = createTitle(name = "T", mediaType = MediaTypeEntity.BOOK.name)
        val keep = Tag(name = "Keep", bg_color = "#aaaaaa").apply { save() }
        val drop = Tag(name = "Drop", bg_color = "#bbbbbb").apply { save() }
        val add = Tag(name = "Add", bg_color = "#cccccc").apply { save() }
        // Pre-existing: keep + drop. Desired: keep + add.
        TitleTag(title_id = title.id!!, tag_id = keep.id!!).save()
        TitleTag(title_id = title.id!!, tag_id = drop.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setTitleTags(setTitleTagsRequest {
                titleId = title.id!!
                tagIds.add(keep.id!!)
                tagIds.add(add.id!!)
            })
            val finalIds = TitleTag.findAll()
                .filter { it.title_id == title.id }
                .map { it.tag_id }
                .toSet()
            assertEquals(setOf(keep.id!!, add.id!!), finalIds,
                "drop removed; add inserted; keep untouched")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setTrackTags ----------------------

    @Test
    fun `setTrackTags requires admin and rejects unknown track id`() = runBlocking {
        val viewer = createViewerUser(username = "strt-viewer")
        val viewerChannel = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(viewerChannel)
            val ex = assertFailsWith<StatusException> {
                stub.setTrackTags(setTrackTagsRequest { trackId = 1 })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            viewerChannel.shutdownNow()
        }

        val admin = createAdminUser(username = "strt-admin-404")
        val adminChannel = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(adminChannel)
            val ex = assertFailsWith<StatusException> {
                stub.setTrackTags(setTrackTagsRequest { trackId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            adminChannel.shutdownNow()
        }
    }

    @Test
    fun `setTrackTags reconciles desired and existing tag ids`() = runBlocking {
        val admin = createAdminUser(username = "strt-reconcile")
        val (_, tid) = seedAlbumWithTrack()
        val keep = Tag(name = "Keep", bg_color = "#a").apply { save() }
        val drop = Tag(name = "Drop", bg_color = "#b").apply { save() }
        val add = Tag(name = "Add", bg_color = "#c").apply { save() }
        TrackTag(track_id = tid, tag_id = keep.id!!).save()
        TrackTag(track_id = tid, tag_id = drop.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setTrackTags(setTrackTagsRequest {
                trackId = tid
                tagIds.add(keep.id!!)
                tagIds.add(add.id!!)
            })
            val finalIds = TrackTag.findAll()
                .filter { it.track_id == tid }
                .map { it.tag_id }
                .toSet()
            assertEquals(setOf(keep.id!!, add.id!!), finalIds)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listTagsForTrack ----------------------

    @Test
    fun `listTagsForTrack returns NOT_FOUND for unknown track id`() = runBlocking {
        val viewer = createViewerUser(username = "ltt-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.listTagsForTrack(trackIdRequest { trackId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTagsForTrack returns the tags linked to the track via TrackTag`() = runBlocking {
        val viewer = createViewerUser(username = "ltt-ok")
        val (_, tid) = seedAlbumWithTrack()
        val tag1 = Tag(name = "Studio", bg_color = "#a").apply { save() }
        val tag2 = Tag(name = "Live", bg_color = "#b").apply { save() }
        TrackTag(track_id = tid, tag_id = tag1.id!!).save()
        TrackTag(track_id = tid, tag_id = tag2.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTagsForTrack(trackIdRequest { trackId = tid })
            val names = resp.tagsList.map { it.name }.toSet()
            assertEquals(setOf("Studio", "Live"), names)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getGenreDetail ----------------------

    @Test
    fun `getGenreDetail returns NOT_FOUND for unknown genre id`() = runBlocking {
        val viewer = createViewerUser(username = "gd-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getGenreDetail(genreIdRequest { genreId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getGenreDetail returns the genre name with no titles when no playable links exist`() = runBlocking {
        val viewer = createViewerUser(username = "gd-ok")
        val genre = Genre(name = "Sci-Fi").apply { save() }
        // BOOK title linked to genre — buildPlayableTitleList only returns
        // titles with playable transcodes, so a BOOK won't show up here.
        val title = createTitle(name = "Book",
            mediaType = MediaTypeEntity.BOOK.name)
        TitleGenre(title_id = title.id!!, genre_id = genre.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getGenreDetail(genreIdRequest { genreId = genre.id!! })
            assertEquals("Sci-Fi", resp.name)
            assertEquals(0, resp.titlesCount,
                "BOOK titles aren't playable → genre detail filters them out")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- requestRetranscode / requestLowStorageTranscode ----------------------

    @Test
    fun `requestRetranscode returns NOT_FOUND for unknown title id`() = runBlocking {
        val viewer = createViewerUser(username = "rrt-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.requestRetranscode(titleIdRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `requestRetranscode flags every transcode that wasn't already requested`() = runBlocking {
        val viewer = createViewerUser(username = "rrt-ok")
        val title = createTitle(name = "Title",
            mediaType = MediaTypeEntity.BOOK.name)
        // Two transcodes — one already requested, one fresh.
        val t1 = createTranscode(titleId = title.id!!,
            filePath = "/movies/a.mkv").apply {
            retranscode_requested = true; save()
        }
        val t2 = createTranscode(titleId = title.id!!,
            filePath = "/movies/b.mkv")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.requestRetranscode(titleIdRequest { titleId = title.id!! })
            assertTrue(resp.queued)
            assertEquals(1, resp.count, "only the fresh transcode flips")
            // Now both rows have the flag set.
            assertTrue(net.stewart.mediamanager.entity.Transcode.findById(t1.id!!)!!
                .retranscode_requested)
            assertTrue(net.stewart.mediamanager.entity.Transcode.findById(t2.id!!)!!
                .retranscode_requested)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `requestLowStorageTranscode skips rows that are already available or already requested`() = runBlocking {
        val viewer = createViewerUser(username = "rlst-ok")
        val title = createTitle(name = "Title",
            mediaType = MediaTypeEntity.BOOK.name)
        val available = createTranscode(titleId = title.id!!,
            filePath = "/movies/a.mkv").apply {
            for_mobile_available = true; save()
        }
        val alreadyRequested = createTranscode(titleId = title.id!!,
            filePath = "/movies/b.mkv").apply {
            for_mobile_requested = true; save()
        }
        val fresh = createTranscode(titleId = title.id!!,
            filePath = "/movies/c.mkv")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.requestLowStorageTranscode(titleIdRequest {
                titleId = title.id!!
            })
            assertTrue(resp.queued)
            assertEquals(1, resp.count, "only the fresh row flips")
            // Spot check: pre-existing flags stayed and the fresh row got
            // for_mobile_requested = true.
            assertTrue(net.stewart.mediamanager.entity.Transcode.findById(available.id!!)!!
                .for_mobile_available)
            assertFalse(net.stewart.mediamanager.entity.Transcode.findById(available.id!!)!!
                .for_mobile_requested,
                "available row stays as-is — not re-requested")
            assertTrue(net.stewart.mediamanager.entity.Transcode.findById(fresh.id!!)!!
                .for_mobile_requested)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `requestLowStorageTranscode returns queued=false when nothing is eligible`() = runBlocking {
        val viewer = createViewerUser(username = "rlst-empty")
        val title = createTitle(name = "Empty",
            mediaType = MediaTypeEntity.BOOK.name)
        // Title with no transcodes → nothing to flip.

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.requestLowStorageTranscode(titleIdRequest {
                titleId = title.id!!
            })
            assertFalse(resp.queued)
            assertEquals(0, resp.count)
        } finally {
            authed.shutdownNow()
        }
    }
}
