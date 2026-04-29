package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Chapter
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.ListeningProgressService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.ReadingProgressService
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [PlaybackGrpcService] — video / audio / book
 * progress RPCs and the chapter/skip-segment listing.
 */
class PlaybackGrpcServiceTest : GrpcTestBase() {

    private fun seedAlbumWithTrack(name: String = "Album",
                                    contentRating: String? = "PG"): Track {
        val title = createTitle(name = name, mediaType = MediaType.ALBUM.name,
            contentRating = contentRating)
        val track = Track(
            title_id = title.id!!,
            track_number = 1,
            disc_number = 1,
            name = "Track 1",
            duration_seconds = 180
        )
        track.save()
        return track
    }

    private fun seedBook(name: String = "Foundation",
                         contentRating: String? = "PG"): MediaItem {
        val title = createTitle(name = name, mediaType = MediaType.BOOK.name,
            contentRating = contentRating)
        val item = MediaItem(
            file_path = "/foundation.epub",
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()
        return item
    }

    // ---------------------- video progress ----------------------

    @Test
    fun `getProgress returns a default-shaped row when nothing has been recorded`() = runBlocking {
        val viewer = createViewerUser(username = "noprogress")
        val title = createTitle(name = "X", contentRating = "PG")
        val tc = createTranscode(title.id!!, "/x.mkv")

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val resp = stub.getProgress(transcodeIdRequest { transcodeId = tc.id!! })
            assertEquals(tc.id!!, resp.transcodeId)
            assertEquals(0.0, resp.position.seconds)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `reportProgress records and clearProgress removes`() = runBlocking {
        val viewer = createViewerUser(username = "reportprogress")
        val title = createTitle(name = "X", contentRating = "PG")
        val tc = createTranscode(title.id!!, "/x.mkv")

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            stub.reportProgress(reportProgressRequest {
                transcodeId = tc.id!!
                position = playbackOffset { seconds = 30.0 }
                duration = playbackOffset { seconds = 7200.0 }
            })
            assertEquals(30.0,
                PlaybackProgressService.getProgressForUser(viewer.id!!, tc.id!!)!!.position_seconds)

            stub.clearProgress(transcodeIdRequest { transcodeId = tc.id!! })
            assertNull(PlaybackProgressService.getProgressForUser(viewer.id!!, tc.id!!))
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getProgress returns NOT_FOUND when the title is hidden or above rating ceiling`() = runBlocking {
        val limited = createViewerUser(username = "limitedviewer").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        val tvMa = createTitle(name = "Mature", contentRating = "TV-MA")
        val tc = createTranscode(tvMa.id!!, "/m.mkv")

        val authedChannel = authenticatedChannel(limited)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getProgress(transcodeIdRequest { transcodeId = tc.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- chapters ----------------------

    @Test
    fun `getChapters returns chapters sorted by start and skip segments mapped by type`() = runBlocking {
        val viewer = createViewerUser(username = "chapviewer")
        val title = createTitle(name = "Chaptered", contentRating = "PG")
        val tc = createTranscode(title.id!!, "/c.mkv")

        // Out-of-order chapters — service must sort them by start.
        Chapter(transcode_id = tc.id!!, chapter_number = 2,
            start_seconds = 1200.0, end_seconds = 2400.0,
            title = "Act II").save()
        Chapter(transcode_id = tc.id!!, chapter_number = 1,
            start_seconds = 0.0, end_seconds = 1200.0,
            title = "Act I").save()

        // One of each type + one unknown -> mapped exhaustively.
        SkipSegment(transcode_id = tc.id!!, segment_type = "intro",
            start_seconds = 0.0, end_seconds = 60.0).save()
        SkipSegment(transcode_id = tc.id!!, segment_type = "credits",
            start_seconds = 2400.0, end_seconds = 2520.0).save()
        SkipSegment(transcode_id = tc.id!!, segment_type = "recap",
            start_seconds = 60.0, end_seconds = 90.0).save()
        SkipSegment(transcode_id = tc.id!!, segment_type = "WEIRD",
            start_seconds = 90.0, end_seconds = 100.0).save()

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val resp = stub.getChapters(transcodeIdRequest { transcodeId = tc.id!! })

            assertEquals(2, resp.chaptersCount)
            assertEquals("Act I", resp.chaptersList[0].title)
            assertEquals(0.0, resp.chaptersList[0].start.seconds)
            assertEquals("Act II", resp.chaptersList[1].title)

            val typesBySeg = resp.skipSegmentsList.associateBy { it.start.seconds }
            assertEquals(SkipSegmentType.SKIP_SEGMENT_TYPE_INTRO,
                typesBySeg[0.0]?.segmentType)
            assertEquals(SkipSegmentType.SKIP_SEGMENT_TYPE_RECAP,
                typesBySeg[60.0]?.segmentType)
            assertEquals(SkipSegmentType.SKIP_SEGMENT_TYPE_UNKNOWN,
                typesBySeg[90.0]?.segmentType, "unrecognized type maps to UNKNOWN")
            assertEquals(SkipSegmentType.SKIP_SEGMENT_TYPE_CREDITS,
                typesBySeg[2400.0]?.segmentType)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- listening progress ----------------------

    @Test
    fun `listening progress round-trips through track_id`() = runBlocking {
        val viewer = createViewerUser(username = "listener")
        val track = seedAlbumWithTrack()

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            // Default-shaped when nothing recorded.
            val initial = stub.getListeningProgress(listeningProgressRequest {
                trackId = track.id!!
            })
            assertEquals(track.id!!, initial.trackId)

            stub.reportListeningProgress(reportListeningProgressRequest {
                trackId = track.id!!
                position = playbackOffset { seconds = 45.0 }
                duration = playbackOffset { seconds = 180.0 }
            })
            val saved = ListeningProgressService.get(viewer.id!!, track.id!!)!!
            assertEquals(45, saved.position_seconds)
            assertEquals(180, saved.duration_seconds)

            stub.clearListeningProgress(listeningProgressRequest {
                trackId = track.id!!
            })
            assertNull(ListeningProgressService.get(viewer.id!!, track.id!!))
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listening progress with media_item_id returns UNIMPLEMENTED`() = runBlocking {
        val viewer = createViewerUser(username = "audiobook-mediaitem")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getListeningProgress(listeningProgressRequest {
                    mediaItemId = 1
                })
            }
            assertEquals(Status.Code.UNIMPLEMENTED, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listening progress without track_id or media_item_id returns INVALID_ARGUMENT`() = runBlocking {
        val viewer = createViewerUser(username = "listener-noid")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getListeningProgress(listeningProgressRequest { })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listening progress NOT_FOUND when track_id doesn't exist`() = runBlocking {
        val viewer = createViewerUser(username = "listener-unknown")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getListeningProgress(listeningProgressRequest { trackId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- reading progress ----------------------

    @Test
    fun `reading progress round-trips through media_item_id`() = runBlocking {
        val viewer = createViewerUser(username = "reader")
        val item = seedBook()

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            // Default-shaped when nothing recorded.
            val initial = stub.getReadingProgress(readingProgressRequest {
                mediaItemId = item.id!!
            })
            assertEquals(item.id!!, initial.mediaItemId)
            assertEquals("", initial.locator)

            stub.reportReadingProgress(reportReadingProgressRequest {
                mediaItemId = item.id!!
                locator = "epubcfi(/6/4!/4/1:0)"
                fraction = 0.42
            })
            val saved = ReadingProgressService.get(viewer.id!!, item.id!!)!!
            assertEquals("epubcfi(/6/4!/4/1:0)", saved.cfi)
            assertEquals(0.42, saved.percent)

            stub.clearReadingProgress(readingProgressRequest {
                mediaItemId = item.id!!
            })
            assertNull(ReadingProgressService.get(viewer.id!!, item.id!!))
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `reading progress NOT_FOUND when media_item doesn't exist`() = runBlocking {
        val viewer = createViewerUser(username = "reader-unknown")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getReadingProgress(readingProgressRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `reading progress NOT_FOUND when media_item has no linked title`() = runBlocking {
        val viewer = createViewerUser(username = "reader-orphan")
        val item = MediaItem(
            file_path = "/orphan.epub",
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }
        // No MediaItemTitle row.

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getReadingProgress(readingProgressRequest { mediaItemId = item.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `reading progress NOT_FOUND when linked title is above rating ceiling`() = runBlocking {
        val limited = createViewerUser(username = "reader-limited").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        val item = seedBook(name = "Mature Book", contentRating = "TV-MA")
        val authedChannel = authenticatedChannel(limited)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getReadingProgress(readingProgressRequest { mediaItemId = item.id!! })
            }
            // Service masks rating denial as NOT_FOUND for ebooks (matches the
            // pattern in requireAccessToMediaItem).
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `reportReadingProgress without explicit fraction defaults to zero`() = runBlocking {
        val viewer = createViewerUser(username = "reader-nofrac")
        val item = seedBook()

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)
            stub.reportReadingProgress(reportReadingProgressRequest {
                mediaItemId = item.id!!
                locator = "/page/1"
                // No fraction set.
            })
            val saved = ReadingProgressService.get(viewer.id!!, item.id!!)!!
            assertEquals(0.0, saved.percent)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
