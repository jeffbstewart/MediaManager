package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge-and-error-path coverage for [DownloadGrpcService]. The happy book-
 * download path lives in [DownloadBookFileTest] (writes real bytes to a
 * temp file and reassembles them); this class fills in the listing,
 * manifest, and access-control branches that don't require real files.
 */
class DownloadGrpcServiceTest : GrpcTestBase() {

    // ---------------------- suggestedBookFilename (pure logic) ----------------------

    @Test
    fun `suggestedBookFilename maps formats to extensions and sanitizes the title`() {
        val svc = DownloadGrpcService()

        assertEquals("Foundation.epub",
            svc.suggestedBookFilename("Foundation", MediaFormat.EBOOK_EPUB.name))
        assertEquals("Foundation.pdf",
            svc.suggestedBookFilename("Foundation", MediaFormat.EBOOK_PDF.name))
        assertEquals("Foundation.m4b",
            svc.suggestedBookFilename("Foundation", MediaFormat.AUDIOBOOK_DIGITAL.name))
        // Unknown format -> .bin.
        assertEquals("Foundation.bin",
            svc.suggestedBookFilename("Foundation", "MARGARITA"))

        // Path-traversal characters and ASCII control chars stripped; whitespace
        // preserved.
        assertEquals("The MatrixReloaded.epub",
            svc.suggestedBookFilename("The Matrix:Reloaded", MediaFormat.EBOOK_EPUB.name))
        assertEquals("Badname.epub",
            svc.suggestedBookFilename("Bad/\\name", MediaFormat.EBOOK_EPUB.name),
            "path separators stripped without whitespace insertion")

        // Blank or whitespace-only name -> "book".
        assertEquals("book.epub",
            svc.suggestedBookFilename("   ", MediaFormat.EBOOK_EPUB.name))
        assertEquals("book.epub",
            svc.suggestedBookFilename("***", MediaFormat.EBOOK_EPUB.name))

        // Truncation at 120 chars before extension.
        val long = "x".repeat(200)
        val out = svc.suggestedBookFilename(long, MediaFormat.EBOOK_EPUB.name)
        assertEquals("${"x".repeat(120)}.epub", out)
    }

    // ---------------------- listAvailable ----------------------

    @Test
    fun `listAvailable returns ForMobile-available transcodes the user can see`() = runBlocking {
        val viewer = createViewerUser(username = "downloader")

        // Available + visible -> in the list.
        val movieA = createTitle(name = "Movie A", contentRating = "PG")
        createTranscode(titleId = movieA.id!!, filePath = "/a.mp4")
            .apply { for_mobile_available = true; save() }

        // Hidden title -> filtered out.
        val hidden = createTitle(name = "Hidden", contentRating = "PG").apply {
            this.hidden = true; save()
        }
        createTranscode(titleId = hidden.id!!, filePath = "/h.mp4")
            .apply { for_mobile_available = true; save() }

        // Not ForMobile-available -> filtered out.
        val notReady = createTitle(name = "Not Ready", contentRating = "PG")
        createTranscode(titleId = notReady.id!!, filePath = "/n.mp4")
            // for_mobile_available defaults to false.

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val resp = stub.listAvailable(Empty.getDefaultInstance())
            assertEquals(1, resp.downloadsCount)
            val item = resp.downloadsList.single()
            assertEquals(movieA.id, item.titleId)
            assertEquals("Movie A", item.titleName)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listAvailable filters by user rating ceiling`() = runBlocking {
        // Limit the viewer to TV-PG (ordinal 3). TV-MA at ordinal 5 must be filtered.
        val viewer = createViewerUser(username = "limited").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }

        val pg = createTitle(name = "PG Movie", contentRating = "PG")
        createTranscode(titleId = pg.id!!, filePath = "/pg.mp4")
            .apply { for_mobile_available = true; save() }
        val tvMa = createTitle(name = "TV-MA Show", contentRating = "TV-MA")
        createTranscode(titleId = tvMa.id!!, filePath = "/ma.mp4")
            .apply { for_mobile_available = true; save() }

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val resp = stub.listAvailable(Empty.getDefaultInstance())
            val titleNames = resp.downloadsList.map { it.titleName }
            assertTrue("PG Movie" in titleNames)
            assertFalse("TV-MA Show" in titleNames)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- getManifest / batchManifest ----------------------

    @Test
    fun `getManifest returns NOT_FOUND for an unknown transcode id`() = runBlocking {
        val viewer = createViewerUser(username = "manifest-anon")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getManifest(manifestRequest { transcodeId = 987_654 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `batchManifest returns an empty list when all ids are unknown`() = runBlocking {
        val viewer = createViewerUser(username = "batch-empty")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val resp = stub.batchManifest(batchManifestRequest {
                transcodeIds.addAll(listOf(1L, 2L, 3L))
            })
            // None resolve -> empty manifests list, not an error.
            assertEquals(0, resp.manifestsCount)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- getBookManifest error paths ----------------------

    @Test
    fun `getBookManifest returns NOT_FOUND for an unknown media item id`() = runBlocking {
        val viewer = createViewerUser(username = "bookmanifest-unknown")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getBookManifest(bookManifestRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getBookManifest returns FAILED_PRECONDITION for a media item without file_path`() = runBlocking {
        val viewer = createViewerUser(username = "bookmanifest-nopath")
        val item = MediaItem(
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = null, // physical edition shape — no file
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getBookManifest(bookManifestRequest { mediaItemId = item.id!! })
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getBookManifest returns FAILED_PRECONDITION for a non-book media format`() = runBlocking {
        val viewer = createViewerUser(username = "bookmanifest-notbook")
        val item = MediaItem(
            media_format = MediaFormat.BLURAY.name, // not in BOOK_FORMATS
            file_path = "/some/where.mkv",
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getBookManifest(bookManifestRequest { mediaItemId = item.id!! })
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getBookManifest returns NOT_FOUND when the media item has no linked title`() = runBlocking {
        val viewer = createViewerUser(username = "bookmanifest-orphan")
        val item = MediaItem(
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = "/orphan.epub",
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }
        // No MediaItemTitle row — orphan.

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getBookManifest(bookManifestRequest { mediaItemId = item.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getBookManifest enforces rating ceiling against the linked title`() = runBlocking {
        val viewer = createViewerUser(username = "bookmanifest-rating").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        val title = createTitle(name = "Mature Book",
            mediaType = MediaType.BOOK.name, contentRating = "TV-MA")
        val item = MediaItem(
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = "/mature.epub",
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        ).apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getBookManifest(bookManifestRequest { mediaItemId = item.id!! })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
