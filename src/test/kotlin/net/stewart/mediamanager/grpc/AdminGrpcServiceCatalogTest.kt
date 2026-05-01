package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.service.WishListService
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2 of [AdminGrpcService] coverage — title-management RPCs
 * (re-enrich, delete, update metadata) plus the read-only listings
 * (recent scans, linked transcodes, unmatched files, rip backlog,
 * purchase wishes).
 */
class AdminGrpcServiceCatalogTest : GrpcTestBase() {

    @Before
    fun cleanExtraTables() {
        DiscoveredFile.deleteAll()
    }

    // ---------------------- reEnrich ----------------------

    @Test
    fun `reEnrich resets enrichment_status to PENDING and clears retry_after`() = runBlocking {
        val admin = createAdminUser(username = "admin-reenrich")
        val title = createTitle(name = "ReEnrichTarget",
            enrichmentStatus = EnrichmentStatus.FAILED.name).apply {
            retry_after = LocalDateTime.now().plusDays(1); save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.reEnrich(titleIdRequest { titleId = title.id!! })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(EnrichmentStatus.PENDING.name, refreshed.enrichment_status)
            assertNull(refreshed.retry_after)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reEnrich returns NOT_FOUND for unknown title id`() = runBlocking {
        val admin = createAdminUser(username = "admin-reenrich-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.reEnrich(titleIdRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deleteTitle ----------------------

    @Test
    fun `deleteTitle cascades through tags, transcodes, episodes, and seasons`() = runBlocking {
        val admin = createAdminUser(username = "admin-deltitle")
        val title = createTitle(name = "Doomed", mediaType = MediaTypeEntity.TV.name)
        val tc = createTranscode(title.id!!, "/d.mkv")
        TitleSeason(title_id = title.id!!, season_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteTitle(titleIdRequest { titleId = title.id!! })

            assertNull(Title.findById(title.id!!))
            assertEquals(0, TitleSeason.findAll().count { it.title_id == title.id })
            // Transcodes for this title gone too.
            assertNull(net.stewart.mediamanager.entity.Transcode.findById(tc.id!!))
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteTitle returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-deltitle-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteTitle(titleIdRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateTitleMetadata ----------------------

    @Test
    fun `updateTitleMetadata mutates only the fields that are set on the request`() = runBlocking {
        val admin = createAdminUser(username = "admin-updmeta")
        val title = createTitle(name = "OldName", releaseYear = 2010).apply {
            description = "Old description"; save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateTitleMetadata(updateTitleMetadataRequest {
                titleId = title.id!!
                name = "NewName"
                // description and releaseYear left unset — preserved.
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals("NewName", refreshed.name)
            assertEquals("Old description", refreshed.description,
                "unset fields preserved")
            assertEquals(2010, refreshed.release_year)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTitleMetadata returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-updmeta-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateTitleMetadata(updateTitleMetadataRequest {
                    titleId = 999_999
                    name = "x"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listLinkedTranscodes ----------------------

    @Test
    fun `listLinkedTranscodes paginates titles sorted by lowercased name`() = runBlocking {
        val admin = createAdminUser(username = "admin-linked")
        // Three titles, deliberately scrambled names for sort.
        val zebra = createTitle(name = "Zebra")
        val apple = createTitle(name = "Apple")
        val banana = createTitle(name = "Banana")
        createTranscode(zebra.id!!, "/z.mkv")
        createTranscode(apple.id!!, "/a.mkv")
        createTranscode(banana.id!!, "/b.mkv")
        // One transcode without file_path — excluded.
        createTranscode(apple.id!!, filePath = null)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listLinkedTranscodes(paginationRequest { limit = 10 })
            assertEquals(3, resp.transcodesCount,
                "transcodes without file_path are excluded")
            assertEquals(listOf("Apple", "Banana", "Zebra"),
                resp.transcodesList.map { it.titleName },
                "lowercased-name ascending sort")
            assertEquals(3, resp.pagination.total)
            assertEquals(1, resp.pagination.page)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listLinkedTranscodes second page returns the remainder`() = runBlocking {
        val admin = createAdminUser(username = "admin-linked-page2")
        for (i in 1..5) {
            val t = createTitle(name = "T$i")
            createTranscode(t.id!!, "/t$i.mkv")
        }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val page2 = stub.listLinkedTranscodes(paginationRequest { page = 2; limit = 3 })
            assertEquals(2, page2.transcodesCount)
            assertEquals(5, page2.pagination.total)
            assertEquals(2, page2.pagination.totalPages)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- unlinkTranscode ----------------------

    @Test
    fun `unlinkTranscode resets matching DiscoveredFile rows to UNMATCHED`() = runBlocking {
        val admin = createAdminUser(username = "admin-unlink")
        val title = createTitle(name = "Linked")
        val tc = createTranscode(title.id!!, "/x.mkv")
        DiscoveredFile(file_path = "/x.mkv", file_name = "x.mkv",
            directory = "/",
            match_status = DiscoveredFileStatus.MATCHED.name,
            matched_title_id = title.id).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.unlinkTranscode(transcodeIdRequest { transcodeId = tc.id!! })

            // Transcode gone.
            assertNull(net.stewart.mediamanager.entity.Transcode.findById(tc.id!!))
            // DiscoveredFile reset.
            val df = DiscoveredFile.findAll().single()
            assertEquals(DiscoveredFileStatus.UNMATCHED.name, df.match_status)
            assertNull(df.matched_title_id)
            assertNull(df.match_method)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `unlinkTranscode returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-unlink-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.unlinkTranscode(transcodeIdRequest { transcodeId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listUnmatchedFiles ----------------------

    @Test
    fun `listUnmatchedFiles returns only DiscoveredFile rows in UNMATCHED status`() = runBlocking {
        val admin = createAdminUser(username = "admin-unmatched")
        DiscoveredFile(file_path = "/u.mkv", file_name = "u.mkv", directory = "/",
            match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = "Unknown Title").apply { save() }
        DiscoveredFile(file_path = "/m.mkv", file_name = "m.mkv", directory = "/",
            match_status = DiscoveredFileStatus.MATCHED.name).apply { save() }
        DiscoveredFile(file_path = "/i.mkv", file_name = "i.mkv", directory = "/",
            match_status = DiscoveredFileStatus.IGNORED.name).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUnmatchedFiles(Empty.getDefaultInstance())
            assertEquals(1, resp.unmatchedCount)
            assertEquals("/u.mkv", resp.unmatchedList.single().filePath)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listUnmatchedFiles attaches a fuzzy-matched suggestion when one exists`() = runBlocking {
        val admin = createAdminUser(username = "admin-unmatched-suggest")
        val title = createTitle(name = "Inception")
        DiscoveredFile(file_path = "/movies/inception.mkv",
            file_name = "inception.mkv", directory = "/movies",
            match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = "Inception").apply { save() }
        // No parsed_title → suggestion stays empty (skip-the-fuzzy branch).
        DiscoveredFile(file_path = "/movies/blank.mkv",
            file_name = "blank.mkv", directory = "/movies",
            match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = null).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUnmatchedFiles(Empty.getDefaultInstance())
            assertEquals(2, resp.unmatchedCount)
            val withSuggestion = resp.unmatchedList
                .single { it.filePath == "/movies/inception.mkv" }
            assertEquals("Inception", withSuggestion.suggestedTitle)
            assertEquals(title.id!!, withSuggestion.suggestedTitleId)
            assertTrue(withSuggestion.matchScore > 0.0)
            // The blank-parsed-title row stays without a suggestion.
            val withoutSuggestion = resp.unmatchedList
                .single { it.filePath == "/movies/blank.mkv" }
            assertEquals("", withoutSuggestion.suggestedTitle)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listRipBacklog ----------------------

    @Test
    fun `listRipBacklog returns owned ENRICHED titles with no linked transcode`() = runBlocking {
        val admin = createAdminUser(username = "admin-ripbacklog")
        // Title A: enriched, owned, no transcode -> in backlog.
        val a = createTitle(name = "Apple",
            enrichmentStatus = EnrichmentStatus.ENRICHED.name)
        val itemA = MediaItem().apply { save() }
        MediaItemTitle(media_item_id = itemA.id!!, title_id = a.id!!).save()

        // Title B: enriched, owned, has transcode -> excluded.
        val b = createTitle(name = "Banana",
            enrichmentStatus = EnrichmentStatus.ENRICHED.name)
        val itemB = MediaItem().apply { save() }
        MediaItemTitle(media_item_id = itemB.id!!, title_id = b.id!!).save()
        createTranscode(b.id!!, "/b.mkv")

        // Title C: pending enrichment -> excluded.
        val c = createTitle(name = "Cherry",
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        val itemC = MediaItem().apply { save() }
        MediaItemTitle(media_item_id = itemC.id!!, title_id = c.id!!).save()

        // Title D: enriched, NOT owned -> excluded.
        createTitle(name = "Date", enrichmentStatus = EnrichmentStatus.ENRICHED.name)

        // Title E: hidden -> excluded.
        val e = createTitle(name = "Elderberry",
            enrichmentStatus = EnrichmentStatus.ENRICHED.name).apply {
            hidden = true; save()
        }
        val itemE = MediaItem().apply { save() }
        MediaItemTitle(media_item_id = itemE.id!!, title_id = e.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listRipBacklog(paginationRequest { limit = 10 })
            assertEquals(1, resp.itemsCount)
            assertEquals("Apple", resp.itemsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listRipBacklog sorts by wish count desc, then by name`() = runBlocking {
        val admin = createAdminUser(username = "admin-ripbacklog-sort")
        // Two items in backlog. Lo-priority "Alpha" + hi-priority "Zulu"
        // — wish-count beats alphabetical so Zulu wins.
        val alpha = createTitle(name = "Alpha",
            enrichmentStatus = EnrichmentStatus.ENRICHED.name)
        val zulu = createTitle(name = "Zulu",
            enrichmentStatus = EnrichmentStatus.ENRICHED.name)
        for (t in listOf(alpha, zulu)) {
            val item = MediaItem().apply { save() }
            MediaItemTitle(media_item_id = item.id!!, title_id = t.id!!).save()
        }
        // Two viewers wish for Zulu; none for Alpha.
        val v1 = createViewerUser(username = "v1-rb")
        val v2 = createViewerUser(username = "v2-rb")
        WishListService.addTranscodeWishForUser(v1.id!!, zulu.id!!)
        WishListService.addTranscodeWishForUser(v2.id!!, zulu.id!!)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listRipBacklog(paginationRequest { limit = 10 })
            assertEquals(2, resp.itemsCount)
            assertEquals("Zulu", resp.itemsList[0].name,
                "wish count beats alphabetical")
            assertEquals(2, resp.itemsList[0].wishCount)
            assertEquals("Alpha", resp.itemsList[1].name)
            assertEquals(0, resp.itemsList[1].wishCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listPurchaseWishes ----------------------

    @Test
    fun `listPurchaseWishes returns aggregated cross-user wish vote counts`() = runBlocking {
        val admin = createAdminUser(username = "admin-purchasewishes")
        val v1 = createViewerUser(username = "pwish-v1")
        val v2 = createViewerUser(username = "pwish-v2")
        // Both vote for tmdb=303 movie.
        WishListService.addMediaWishForUser(v1.id!!,
            TmdbId(303, MediaTypeEntity.MOVIE), "Sci-Fi Flick", null, 2024, 50.0)
        WishListService.addMediaWishForUser(v2.id!!,
            TmdbId(303, MediaTypeEntity.MOVIE), "Sci-Fi Flick", null, 2024, 50.0)
        // v1 votes for a second.
        WishListService.addMediaWishForUser(v1.id!!,
            TmdbId(404, MediaTypeEntity.MOVIE), "Solo Vote", null, 2023, 10.0)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listPurchaseWishes(Empty.getDefaultInstance())
            assertEquals(2, resp.wishesCount)
            // Sorted by vote count descending — Sci-Fi Flick (2 votes) first.
            assertEquals(303, resp.wishesList[0].tmdbId)
            assertEquals(2, resp.wishesList[0].voteCount)
            assertEquals(404, resp.wishesList[1].tmdbId)
            assertEquals(1, resp.wishesList[1].voteCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listRecentScans ----------------------

    @Test
    fun `listRecentScans returns empty when no scans recorded`() = runBlocking {
        val admin = createAdminUser(username = "admin-recentscans")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listRecentScans(Empty.getDefaultInstance())
            assertEquals(0, resp.scansCount)
        } finally {
            authed.shutdownNow()
        }
    }
}
