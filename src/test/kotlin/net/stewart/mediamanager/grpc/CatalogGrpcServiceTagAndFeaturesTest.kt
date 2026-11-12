package net.stewart.mediamanager.grpc

import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.ProblemReport
import net.stewart.mediamanager.entity.ReportStatus
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for the buildTagTitleList helper (cross-media-type tag
 * results) and the admin-only branches of buildFeatures.
 */
class CatalogGrpcServiceTagAndFeaturesTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        UnmatchedAudio.deleteAll()
        UnmatchedBook.deleteAll()
        ProblemReport.deleteAll()
        Camera.deleteAll()
        LiveTvTuner.deleteAll()
        TrackTag.deleteAll()
        Track.deleteAll()
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()
    }

    // ---------------------- buildTagTitleList via getTagDetail ----------------------

    @Test
    fun `getTagDetail surfaces tagged BOOK and ALBUM titles via buildTagTitleList`() = runBlocking {
        val viewer = createViewerUser(username = "tt-mixed")
        val tag = Tag(name = "Mood", bg_color = "#aabbcc").apply { save() }

        // BOOK title: only included if linked to a MediaItem with file_path
        // (digital edition). Two book titles — one with, one without.
        val bookDigital = createTitle(name = "Digital Book",
            mediaType = MediaTypeEntity.BOOK.name)
        val bookPhysicalOnly = createTitle(name = "Paper Only Book",
            mediaType = MediaTypeEntity.BOOK.name)
        val ebook = MediaItem(product_name = "EPUB",
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = "/library/book.epub").apply { save() }
        MediaItemTitle(media_item_id = ebook.id!!,
            title_id = bookDigital.id!!, disc_number = 1).save()
        // Paperback row has no file_path — bookPhysicalOnly stays out of the
        // digitalTitleIds set and gets filtered out.
        val paper = MediaItem(product_name = "Paper",
            media_format = MediaFormat.MASS_MARKET_PAPERBACK.name,
            file_path = null).apply { save() }
        MediaItemTitle(media_item_id = paper.id!!,
            title_id = bookPhysicalOnly.id!!, disc_number = 1).save()
        TitleTag(title_id = bookDigital.id!!, tag_id = tag.id!!).save()
        TitleTag(title_id = bookPhysicalOnly.id!!, tag_id = tag.id!!).save()

        // ALBUM: only included if at least one Track has a file_path.
        val albumWithFile = createTitle(name = "Album With File",
            mediaType = MediaTypeEntity.ALBUM.name)
        Track(title_id = albumWithFile.id!!, track_number = 1,
            disc_number = 1, name = "T", file_path = "/music/t.flac").save()
        TitleTag(title_id = albumWithFile.id!!, tag_id = tag.id!!).save()

        val albumNoFile = createTitle(name = "Album No File",
            mediaType = MediaTypeEntity.ALBUM.name)
        Track(title_id = albumNoFile.id!!, track_number = 1,
            disc_number = 1, name = "T", file_path = null).save()
        TitleTag(title_id = albumNoFile.id!!, tag_id = tag.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTagDetail(tagIdRequest { tagId = tag.id!! })
            val names = resp.titlesList.map { it.name }.toSet()
            assertTrue("Digital Book" in names,
                "BOOK with digital edition surfaces")
            assertFalse("Paper Only Book" in names,
                "BOOK without digital edition is filtered out")
            assertTrue("Album With File" in names,
                "ALBUM with a file_path track surfaces")
            assertFalse("Album No File" in names,
                "ALBUM with no track file_path is filtered out")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTagDetail surfaces tracks tagged via TrackTag inside visible albums`() = runBlocking {
        val viewer = createViewerUser(username = "tt-tracks")
        val tag = Tag(name = "Energy", bg_color = "#abc").apply { save() }
        val artist = Artist(name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val album = createTitle(name = "Animals",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "Pigs",
            file_path = "/music/pigs.flac").apply { save() }
        TrackTag(track_id = track.id!!, tag_id = tag.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTagDetail(tagIdRequest { tagId = tag.id!! })
            assertEquals(1, resp.tracksCount)
            val trackHit = resp.tracksList.single()
            assertEquals("Pigs", trackHit.name)
            assertEquals("Animals", trackHit.titleName,
                "track row carries parent album name")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- buildFeatures admin-only counts ----------------------

    @Test
    fun `buildFeatures fills admin badge counts only for admin callers`() = runBlocking {
        // Viewer + admin first so ProblemReport's FK can hang off a real user.
        val viewer = createViewerUser(username = "feat-viewer")
        // Seed data that should bump every admin badge.
        DiscoveredFile(file_path = "/u.mkv", file_name = "u.mkv",
            directory = "/", match_status = DiscoveredFileStatus.UNMATCHED.name)
            .save()
        UnmatchedBook(file_path = "/u.epub", file_name = "u.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).save()
        UnmatchedAudio(file_path = "/u.flac", file_name = "u.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).save()
        // Title with non-ENRICHED status drives the dataQualityCount.
        createTitle(name = "Pending Movie",
            mediaType = MediaTypeEntity.MOVIE.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        ProblemReport(user_id = viewer.id!!, description = "x",
            status = ReportStatus.OPEN.name,
            created_at = LocalDateTime.now()).save()

        // Viewer sees zero on every admin badge.
        val viewerChannel = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(viewerChannel)
            val features = stub.homeFeed(Empty.getDefaultInstance()).features
            assertFalse(features.isAdmin)
            assertEquals(0, features.unmatchedCount)
            assertEquals(0, features.unmatchedBooksCount)
            assertEquals(0, features.unmatchedAudioCount)
            assertEquals(0, features.dataQualityCount)
            assertEquals(0, features.openReportsCount)
        } finally {
            viewerChannel.shutdownNow()
        }

        // Admin sees the actual counts.
        val admin = createAdminUser(username = "feat-admin")
        val adminChannel = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(adminChannel)
            val features = stub.homeFeed(Empty.getDefaultInstance()).features
            assertTrue(features.isAdmin)
            assertEquals(1, features.unmatchedCount)
            assertEquals(1, features.unmatchedBooksCount)
            assertEquals(1, features.unmatchedAudioCount)
            assertEquals(1, features.dataQualityCount)
            assertEquals(1, features.openReportsCount)
        } finally {
            adminChannel.shutdownNow()
        }
    }

    @Test
    fun `buildFeatures has_cameras and has_live_tv flip when an enabled row exists`() = runBlocking {
        Camera(name = "Front Door", rtsp_url = "rtsp://example/cam",
            enabled = true).save()
        LiveTvTuner(name = "Tuner",
            ip_address = "127.0.0.1", enabled = true).save()

        val viewer = createViewerUser(username = "feat-cam-tv")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val features = stub.homeFeed(Empty.getDefaultInstance()).features
            assertTrue(features.hasCameras)
            assertTrue(features.hasLiveTv)
        } finally {
            authed.shutdownNow()
        }
    }
}
