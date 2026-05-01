package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEntity
import net.stewart.mediamanager.entity.BarcodeScan
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEntity
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.WishListService
import org.junit.Before
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Slice 15 of [AdminGrpcService] coverage — the remaining testable
 * surface: data quality issue scoring, purchase-wish status mutation,
 * scan-driven purchase info update, catalog track search, inventory
 * report aggregation, image-migration verifier, and the agent-trigger
 * RPCs.
 */
class AdminGrpcServiceMiscTest : GrpcTestBase() {

    @Before
    fun cleanExtraTables() {
        BarcodeScan.deleteAll()
        OwnershipPhoto.deleteAll()
        TitleArtist.deleteAll()
        Artist.deleteAll()
        Track.deleteAll()
    }

    // ---------------------- listDataQuality ----------------------

    @Test
    fun `listDataQuality flags video-scope titles missing tmdb, content rating, backdrop, cast, genres, poster, description, year`() = runBlocking {
        val admin = createAdminUser(username = "dq-video")
        // A movie with NOTHING populated — should hit every video-scope issue.
        createTitle(name = "BareMovie", mediaType = MediaTypeEntity.MOVIE.name,
            contentRating = null, posterPath = null, releaseYear = null)
            .apply { tmdb_id = null; backdrop_path = null; description = null; save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listDataQuality(dataQualityRequest { })
            val issues = resp.itemsList.single().issuesList.toSet()
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_POSTER in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_DESCRIPTION in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_YEAR in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TMDB_ID in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_BACKDROP in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CAST in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_GENRES in issues)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listDataQuality scope=BOOK swaps the issue set for book-specific checks`() = runBlocking {
        val admin = createAdminUser(username = "dq-book")
        createTitle(name = "BareBook", mediaType = MediaTypeEntity.BOOK.name,
            contentRating = null, posterPath = null, releaseYear = null)
            .apply { description = null; open_library_work_id = null; save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listDataQuality(dataQualityRequest {
                scope = DataQualityScope.DATA_QUALITY_SCOPE_BOOK
            })
            val issues = resp.itemsList.single().issuesList.toSet()
            // Book-specific issues are present.
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_OPENLIBRARY_ID in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_AUTHORS in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING in issues)
            // Video-only issues are absent.
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TMDB_ID in issues)
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CAST in issues)
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_BACKDROP in issues)
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_GENRES in issues)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listDataQuality scope=AUDIO checks MBID, tracks, album_artists`() = runBlocking {
        val admin = createAdminUser(username = "dq-audio")
        createTitle(name = "BareAlbum", mediaType = MediaTypeEntity.ALBUM.name,
            contentRating = null, posterPath = null, releaseYear = null)
            .apply { description = null; musicbrainz_release_group_id = null; save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listDataQuality(dataQualityRequest {
                scope = DataQualityScope.DATA_QUALITY_SCOPE_AUDIO
            })
            val issues = resp.itemsList.single().issuesList.toSet()
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_MUSICBRAINZ_ID in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TRACKS in issues)
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_NO_ALBUM_ARTISTS in issues)
            // Video-only issues absent.
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_TMDB_ID in issues)
            assertFalse(DataQualityIssue.DATA_QUALITY_ISSUE_NO_CONTENT_RATING in issues)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listDataQuality flags FAILED and ABANDONED enrichment status`() = runBlocking {
        val admin = createAdminUser(username = "dq-failed")
        createTitle(name = "Failed",
            enrichmentStatus = EnrichmentStatusEntity.FAILED.name)
        createTitle(name = "Abandoned",
            enrichmentStatus = EnrichmentStatusEntity.ABANDONED.name)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listDataQuality(dataQualityRequest { })
            val byName = resp.itemsList.associateBy { it.name }
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_FAILED
                in byName["Failed"]!!.issuesList.toSet())
            assertTrue(DataQualityIssue.DATA_QUALITY_ISSUE_ENRICHMENT_ABANDONED
                in byName["Abandoned"]!!.issuesList.toSet())
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listDataQuality status filter narrows to a single enrichment status`() = runBlocking {
        val admin = createAdminUser(username = "dq-status-filter")
        createTitle(name = "Pending",
            enrichmentStatus = EnrichmentStatusEntity.PENDING.name)
        createTitle(name = "Enriched",
            enrichmentStatus = EnrichmentStatusEntity.ENRICHED.name)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listDataQuality(dataQualityRequest {
                status = EnrichmentStatus.ENRICHMENT_STATUS_PENDING
            })
            assertEquals(1, resp.itemsCount)
            assertEquals("Pending", resp.itemsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updatePurchaseInfo ----------------------

    @Test
    fun `updatePurchaseInfo writes through ScanDetailService when scan is linked`() = runBlocking {
        val admin = createAdminUser(username = "upi-ok")
        val item = MediaItem(product_name = "Linked").apply { save() }
        val scan = BarcodeScan(upc = "0123456789012",
            media_item_id = item.id,
            scanned_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updatePurchaseInfo(updatePurchaseInfoRequest {
                scanId = scan.id!!
                purchasePlace = "Best Buy"
                purchaseDate = calendarDate {
                    year = 2024; month = Month.MONTH_JANUARY; day = 15
                }
                purchasePrice = 14.99
            })
            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals("Best Buy", refreshed.purchase_place)
            assertEquals(0, refreshed.purchase_price!!.compareTo(BigDecimal("14.99")))
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updatePurchaseWishStatus ----------------------

    @Test
    fun `updatePurchaseWishStatus returns NOT_FOUND when no aggregate matches`() = runBlocking {
        val admin = createAdminUser(username = "upws-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updatePurchaseWishStatus(updatePurchaseWishStatusRequest {
                    tmdbId = 999_999
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                    status = AcquisitionStatus.ACQUISITION_STATUS_ORDERED
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updatePurchaseWishStatus flips AcquisitionStatus on the matching wish aggregate`() = runBlocking {
        val admin = createAdminUser(username = "upws-ok")
        val viewer = createViewerUser(username = "upws-voter")
        // Create a media wish and corresponding TitleSeason so the wish is
        // visible and resolvable.
        WishListService.addMediaWishForUser(viewer.id!!,
            TmdbId(303, MediaTypeEntity.MOVIE), "Wished Movie",
            null, 2024, 50.0)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updatePurchaseWishStatus(updatePurchaseWishStatusRequest {
                tmdbId = 303
                mediaType = MediaType.MEDIA_TYPE_MOVIE
                status = AcquisitionStatus.ACQUISITION_STATUS_ORDERED
            })
            // setAcquisitionStatus creates the Title + TitleSeason rows.
            val title = net.stewart.mediamanager.entity.Title.findAll()
                .single { it.tmdb_id == 303 }
            val season = TitleSeason.findAll()
                .single { it.title_id == title.id }
            assertEquals(net.stewart.mediamanager.entity.AcquisitionStatus.ORDERED.name,
                season.acquisition_status)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- searchCatalogTracks ----------------------

    @Test
    fun `searchCatalogTracks returns empty for query length under 2`() = runBlocking {
        val admin = createAdminUser(username = "sct-tracks-short")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTracks(searchCatalogTracksRequest {
                query = "x"
            })
            assertEquals(0, resp.matchesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchCatalogTracks matches by track name and by album name`() = runBlocking {
        val admin = createAdminUser(username = "sct-tracks-match")
        val album = createTitle(name = "Foundation Tracks",
            mediaType = MediaTypeEntity.ALBUM.name)
        val otherAlbum = createTitle(name = "Other Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        // Track matches by album name (album is "Foundation Tracks").
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Beethoven Sonata").save()
        // Track matches by name (track is "Asimov reading").
        Track(title_id = otherAlbum.id!!, track_number = 1, disc_number = 1,
            name = "Asimov reading").save()
        // Unrelated track.
        Track(title_id = otherAlbum.id!!, track_number = 2, disc_number = 1,
            name = "Other").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTracks(searchCatalogTracksRequest {
                query = "asimov"
            })
            assertEquals(1, resp.matchesCount)
            assertEquals("Asimov reading", resp.matchesList.single().trackName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchCatalogTracks honors limit`() = runBlocking {
        val admin = createAdminUser(username = "sct-tracks-limit")
        val album = createTitle(name = "Foundation Tracks",
            mediaType = MediaTypeEntity.ALBUM.name)
        for (i in 1..5) {
            Track(title_id = album.id!!, track_number = i, disc_number = 1,
                name = "Track $i").save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTracks(searchCatalogTracksRequest {
                query = "foundation"
                limit = 2
            })
            assertEquals(2, resp.matchesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchCatalogTracks surfaces album_artist_name when artist_order=0 link exists`() = runBlocking {
        val admin = createAdminUser(username = "sct-tracks-artist")
        val artist = Artist(name = "Solo Artist", sort_name = "Artist, Solo",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val album = createTitle(name = "Solo Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "TheTrack").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTracks(searchCatalogTracksRequest {
                query = "solo"
            })
            assertEquals("Solo Artist", resp.matchesList.single().albumArtistName)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- generateInventoryReport ----------------------

    @Test
    fun `generateInventoryReport totals purchase + replacement values across every item`() = runBlocking {
        val admin = createAdminUser(username = "inv-report")
        MediaItem(product_name = "AAA",
            purchase_price = BigDecimal("10.00"),
            replacement_value = BigDecimal("15.00")).save()
        MediaItem(product_name = "BBB",
            purchase_price = BigDecimal("20.00")).save()
        MediaItem(product_name = "CCC").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.generateInventoryReport(inventoryReportRequest { })
            assertEquals(3, resp.totalItems)
            assertEquals(3, resp.rowsCount)
            assertEquals(30.0, resp.totalPurchaseValue)
            assertEquals(15.0, resp.totalReplacementValue)
            // Sorted by product_name.lowercase() — alphabetical.
            assertEquals(listOf("AAA", "BBB", "CCC"),
                resp.rowsList.map { it.titleNames })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- verifyFirstPartyImageMigration ----------------------

    @Test
    fun `verifyFirstPartyImageMigration returns a populated report on an empty DB`() = runBlocking {
        val admin = createAdminUser(username = "vfp-empty")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.verifyFirstPartyImageMigration(Empty.getDefaultInstance())
            // No rows -> totalRows == 0; safeToDeleteOldLayout depends on
            // implementation but the structured response is what we lock down.
            assertEquals(0, resp.ownershipPhotos.totalRows)
            assertEquals(0, resp.localImages.totalRows)
            assertTrue(resp.hasAuditedAt())
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- reEnrichWithAgent ----------------------

    @Test
    fun `reEnrichWithAgent returns NOT_FOUND for unknown title id`() = runBlocking {
        val admin = createAdminUser(username = "rewa-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                    titleId = 999_999
                    agent = EnrichmentAgent.ENRICHMENT_AGENT_TMDB
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent fires off a daemon for known title (smoke test)`() = runBlocking {
        // The service kicks off a Thread and returns Empty. The daemon's
        // outcome (status flip) is racy from a test's perspective; we only
        // verify the call accepts a known title and returns without throwing.
        val admin = createAdminUser(username = "rewa-ok")
        val title = createTitle(name = "ReEnrichMe")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                titleId = title.id!!
                agent = EnrichmentAgent.ENRICHMENT_AGENT_TMDB
            })
        } finally {
            authed.shutdownNow()
        }
        Unit
    }
}
