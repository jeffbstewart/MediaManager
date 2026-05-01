package net.stewart.mediamanager.grpc

import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.ListeningProgress
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.ReadingProgress
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.Track
import org.junit.Before
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [CatalogGrpcService.homeFeed] — the SPA's home payload.
 * The empty case covers every "skip when empty" branch; the populated
 * cases drive each helper that surfaces non-trivial output:
 * buildRecentlyAddedBooks, buildRecentlyAddedAlbums, buildResumeReading,
 * buildResumeListening, and the buildFeatures aggregation.
 */
class CatalogGrpcServiceHomeFeedTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        ListeningProgress.deleteAll()
        ReadingProgress.deleteAll()
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()
        BookSeries.deleteAll()
    }

    // ---------------------- empty feed ----------------------

    @Test
    fun `homeFeed for an empty catalog returns empty carousels and no progress lists`() = runBlocking {
        val viewer = createViewerUser(username = "hf-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertEquals(0, resp.carouselsCount,
                "no playable titles → no carousels")
            assertEquals(0, resp.continueWatchingCount)
            assertEquals(0, resp.recentlyAddedCount)
            assertEquals(0, resp.recentlyAddedBooksCount)
            assertEquals(0, resp.recentlyAddedAlbumsCount)
            assertEquals(0, resp.resumeListeningCount)
            assertEquals(0, resp.resumeReadingCount)
            assertEquals(0, resp.recentlyWatchedCount)
            assertEquals(0, resp.missingSeasonsCount)
            // Features always present.
            assertTrue(resp.hasFeatures())
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- recently added books / albums ----------------------

    @Test
    fun `homeFeed surfaces recentlyAddedBooks newest-first with author and series context`() = runBlocking {
        val viewer = createViewerUser(username = "hf-books")
        val author = Author(name = "Asimov", sort_name = "Asimov, Isaac")
            .apply { save() }
        val series = BookSeries(name = "Foundation", author_id = author.id)
            .apply { save() }
        // Two BOOK titles: older + newer, both linked to MediaItems.
        val older = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id
            series_number = BigDecimal(1)
            save()
        }
        val newer = createTitle(name = "Foundation and Empire",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id
            series_number = BigDecimal(2)
            save()
        }
        TitleAuthor(title_id = older.id!!, author_id = author.id!!,
            author_order = 0).save()
        TitleAuthor(title_id = newer.id!!, author_id = author.id!!,
            author_order = 0).save()
        // MediaItem created_at controls the ordering.
        val olderItem = MediaItem(product_name = "Older",
            media_format = MediaFormat.HARDBACK.name,
            created_at = LocalDateTime.now().minusDays(10)).apply { save() }
        MediaItemTitle(media_item_id = olderItem.id!!,
            title_id = older.id!!, disc_number = 1).save()
        val newerItem = MediaItem(product_name = "Newer",
            media_format = MediaFormat.HARDBACK.name,
            created_at = LocalDateTime.now()).apply { save() }
        MediaItemTitle(media_item_id = newerItem.id!!,
            title_id = newer.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertEquals(2, resp.recentlyAddedBooksCount)
            // Newer first (sortedByDescending on MediaItem.created_at).
            assertEquals("Foundation and Empire", resp.recentlyAddedBooksList[0].name)
            assertEquals("Foundation", resp.recentlyAddedBooksList[1].name)
            // Author + series populated on the proto.
            assertEquals("Asimov", resp.recentlyAddedBooksList[0].authorName)
            assertEquals("Foundation", resp.recentlyAddedBooksList[0].seriesName)
            // Features sees the BOOK title and flips hasBooks=true.
            assertTrue(resp.features.hasBooks)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed surfaces recentlyAddedAlbums with the lead artist name`() = runBlocking {
        val viewer = createViewerUser(username = "hf-albums")
        val artist = Artist(name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val album = createTitle(name = "Animals",
            mediaType = MediaTypeEntity.ALBUM.name).apply {
            track_count = 5
            save()
        }
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        val item = MediaItem(product_name = "Animals CD",
            media_format = MediaFormat.CD.name,
            created_at = LocalDateTime.now()).apply { save() }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = album.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertEquals(1, resp.recentlyAddedAlbumsCount)
            assertEquals("Animals", resp.recentlyAddedAlbumsList.single().name)
            assertEquals("Pink Floyd",
                resp.recentlyAddedAlbumsList.single().artistName)
            assertTrue(resp.features.hasMusic)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- resume reading / listening ----------------------

    @Test
    fun `homeFeed surfaces resumeReading entries from per-user reading progress`() = runBlocking {
        val viewer = createViewerUser(username = "hf-reading")
        val title = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name)
        val item = MediaItem(product_name = "EPUB",
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = "/library/foundation.epub").apply { save() }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = title.id!!, disc_number = 1).save()
        ReadingProgress(user_id = viewer.id!!, media_item_id = item.id!!,
            cfi = "epubcfi(/6/4!/4/2)", percent = 0.45,
            updated_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertEquals(1, resp.resumeReadingCount)
            val entry = resp.resumeReadingList.single()
            assertEquals(title.id!!, entry.titleId)
            assertEquals("Foundation", entry.titleName)
            assertEquals(0.45, entry.percent, 0.001)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed surfaces resumeListening entries from per-user listening progress`() = runBlocking {
        val viewer = createViewerUser(username = "hf-listening")
        val artist = Artist(name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val album = createTitle(name = "Animals",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "Pigs on the Wing",
            duration_seconds = 600).apply { save() }
        ListeningProgress(user_id = viewer.id!!, track_id = track.id!!,
            position_seconds = 120, duration_seconds = 600,
            updated_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertEquals(1, resp.resumeListeningCount)
            val entry = resp.resumeListeningList.single()
            assertEquals(track.id!!, entry.trackId)
            assertEquals("Pigs on the Wing", entry.trackName)
            assertEquals("Animals", entry.titleName)
            assertEquals("Pink Floyd", entry.artistName)
            // Percent = 120/600 = 0.2.
            assertEquals(0.2, entry.percent, 0.001)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- buildFeatures admin badges ----------------------

    @Test
    fun `homeFeed buildFeatures lights up books, music, and personal flags by media type`() = runBlocking {
        val viewer = createViewerUser(username = "hf-features")
        createTitle(name = "Some Book",
            mediaType = MediaTypeEntity.BOOK.name)
        createTitle(name = "Some Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        createTitle(name = "Vacation",
            mediaType = MediaTypeEntity.PERSONAL.name)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            assertTrue(resp.features.hasBooks)
            assertTrue(resp.features.hasMusic)
            assertTrue(resp.features.hasPersonalVideos)
        } finally {
            authed.shutdownNow()
        }
    }
}
