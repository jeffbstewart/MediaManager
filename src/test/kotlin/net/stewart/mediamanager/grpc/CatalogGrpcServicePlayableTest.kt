package net.stewart.mediamanager.grpc

import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.service.JimfsRule
import org.junit.Before
import org.junit.Rule
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the CatalogGrpcService branches that previously sat
 * behind a `playable` gate (which checks `File(filePath).exists()`).
 * Now that `isPlayable` routes through `Filesystems.current`, JimfsRule
 * lets us seed an in-memory video file and exercise the video paths
 * the earlier slices had to skip:
 *
 * - `search` MOVIE / TV / PERSONAL result types
 * - `getTitleDetail` for a playable MOVIE
 * - `homeFeed` Resume Playing + Recently Added + Movies + TV + Family carousels
 * - `listFamilyVideos` with `playableOnly = true`
 * - `getActorDetail` ownedTitles list (playable filter)
 */
class CatalogGrpcServicePlayableTest : GrpcTestBase() {

    @get:Rule val fsRule = JimfsRule()

    @Before
    fun cleanCatalogTables() {
        TitleFamilyMember.deleteAll()
        FamilyMember.deleteAll()
        // nas_root_path must be configured for Title.toProto.playable to
        // flip; the search-side isPlayable check works without it for
        // direct-extension files but the title-proto path enforces both.
        AppConfig(config_key = "nas_root_path", config_val = "/nas").save()
    }

    /** Seed one playable on-disk transcode + return its title. */
    private fun seedPlayableMovie(
        name: String = "Inception",
        path: String = "/nas/movies/inception.mp4",
        popularity: Double = 50.0,
    ): Pair<net.stewart.mediamanager.entity.Title, net.stewart.mediamanager.entity.Transcode> {
        fsRule.seed(path)
        val title = createTitle(name = name,
            mediaType = MediaTypeEntity.MOVIE.name,
            popularity = popularity)
        val tc = createTranscode(titleId = title.id!!, filePath = path)
        net.stewart.mediamanager.service.SearchIndexService.onTitleChanged(title.id!!)
        return title to tc
    }

    // ---------------------- search ----------------------

    @Test
    fun `search returns a MOVIE result for a playable mp4 transcode`() = runBlocking {
        val viewer = createViewerUser(username = "search-movie")
        val (title, tc) = seedPlayableMovie()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "inception" })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_MOVIE
            }
            assertEquals("Inception", hit.name)
            assertEquals(title.id!!, hit.titleId)
            assertEquals(tc.id!!, hit.transcodeId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search excludes a movie title when the file does not exist on disk`() = runBlocking {
        val viewer = createViewerUser(username = "search-movie-missing")
        // Title + transcode exist in the catalog but the file isn't seeded.
        val title = createTitle(name = "Not Yet Ripped",
            mediaType = MediaTypeEntity.MOVIE.name)
        createTranscode(titleId = title.id!!, filePath = "/nas/movies/notyet.mp4")
        net.stewart.mediamanager.service.SearchIndexService.onTitleChanged(title.id!!)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "ripped" })
            assertEquals(0, resp.resultsList.count {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_MOVIE
            }, "movie hit gated behind isPlayable; un-seeded file → no hit")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getTitleDetail ----------------------

    @Test
    fun `getTitleDetail for a playable movie reports playable=true on the title proto`() = runBlocking {
        val viewer = createViewerUser(username = "td-movie-playable")
        val (title, _) = seedPlayableMovie(name = "Foundation")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            assertTrue(resp.title.playable, "title.playable flag flipped via isPlayable")
            assertEquals(1, resp.transcodesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- homeFeed video carousels ----------------------

    @Test
    fun `homeFeed Movies carousel surfaces playable movie titles by popularity`() = runBlocking {
        val viewer = createViewerUser(username = "hf-movies")
        seedPlayableMovie(name = "Lower",
            path = "/nas/movies/lower.mp4", popularity = 10.0)
        seedPlayableMovie(name = "Higher",
            path = "/nas/movies/higher.mp4", popularity = 99.0)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            val movies = resp.carouselsList.single { it.name == "Movies" }
            assertEquals(listOf("Higher", "Lower"), movies.itemsList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed Recently Added is keyed off transcode created_at descending`() = runBlocking {
        val viewer = createViewerUser(username = "hf-recent")
        // Seed two playable movies; flip their transcode created_at so
        // the "newer" file shows first.
        val (older, olderTc) = seedPlayableMovie(name = "Older",
            path = "/nas/movies/older.mp4")
        val (newer, newerTc) = seedPlayableMovie(name = "Newer",
            path = "/nas/movies/newer.mp4")
        olderTc.created_at = LocalDateTime.now().minusDays(10)
        olderTc.save()
        newerTc.created_at = LocalDateTime.now()
        newerTc.save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            val recent = resp.carouselsList.single { it.name == "Recently Added" }
            assertEquals(listOf("Newer", "Older"), recent.itemsList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed Resume Playing surfaces titles with active playback progress`() = runBlocking {
        val viewer = createViewerUser(username = "hf-resume")
        val (title, tc) = seedPlayableMovie()
        PlaybackProgress(
            user_id = viewer.id!!,
            transcode_id = tc.id!!,
            position_seconds = 600.0,
            duration_seconds = 7200.0,
            updated_at = LocalDateTime.now(),
        ).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            val resume = resp.carouselsList.single { it.name == "Resume Playing" }
            assertEquals(1, resume.itemsCount)
            assertEquals(title.name, resume.itemsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed Family carousel surfaces playable PERSONAL titles by event_date desc`() = runBlocking {
        val viewer = createViewerUser(username = "hf-family")
        // Two PERSONAL titles with playable mp4s; later event_date wins.
        fsRule.seed("/nas/family/2023.mp4")
        fsRule.seed("/nas/family/2024.mp4")
        val older = createTitle(name = "Vacation 2023",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = net.stewart.mediamanager.entity.EnrichmentStatus.PENDING.name)
            .apply { event_date = java.time.LocalDate.of(2023, 6, 1); save() }
        createTranscode(titleId = older.id!!, filePath = "/nas/family/2023.mp4")
        val newer = createTitle(name = "Vacation 2024",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = net.stewart.mediamanager.entity.EnrichmentStatus.PENDING.name)
            .apply { event_date = java.time.LocalDate.of(2024, 6, 1); save() }
        createTranscode(titleId = newer.id!!, filePath = "/nas/family/2024.mp4")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            val family = resp.carouselsList.single { it.name == "Family" }
            assertEquals(listOf("Vacation 2024", "Vacation 2023"),
                family.itemsList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `homeFeed TV Series carousel surfaces playable TV titles with episode-bound transcodes`() = runBlocking {
        val viewer = createViewerUser(username = "hf-tv")
        // TV titles only count as playable when at least one of their
        // transcodes carries an episode_id (per loadCatalog's TV branch).
        val series = createTitle(name = "Pilot Show",
            mediaType = MediaTypeEntity.TV.name,
            popularity = 75.0)
        TitleSeason(title_id = series.id!!, season_number = 1,
            acquisition_status = "OWNED").save()
        val episode = Episode(title_id = series.id!!, season_number = 1,
            episode_number = 1, name = "Pilot", tmdb_id = 1).apply { save() }
        fsRule.seed("/nas/tv/pilot/s01e01.mp4")
        createTranscode(titleId = series.id!!,
            filePath = "/nas/tv/pilot/s01e01.mp4",
            episodeId = episode.id)
        net.stewart.mediamanager.service.SearchIndexService.onTitleChanged(series.id!!)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.homeFeed(Empty.getDefaultInstance())
            val tv = resp.carouselsList.single { it.name == "TV Series" }
            assertEquals(listOf("Pilot Show"), tv.itemsList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listFamilyVideos playableOnly ----------------------

    @Test
    fun `listFamilyVideos playableOnly hides un-ripped PERSONAL titles`() = runBlocking {
        val viewer = createViewerUser(username = "fv-playable-only")
        // listFamilyVideos's playable check is ext-aware: an .mp4 is
        // considered playable on the strength of `file_path != null`
        // alone (no existence check), while a needs-transcoding source
        // (.mkv) is gated behind TranscoderAgent.isTranscoded, which
        // does check the ForBrowser MP4 against Filesystems.current.
        // Use .mkv sources so playableOnly actually filters.
        fsRule.seed("/nas/family/ripped.mkv")
        fsRule.seed("/nas/ForBrowser/family/ripped.mp4")  // makes isTranscoded → true
        val playable = createTitle(name = "Ripped",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = net.stewart.mediamanager.entity.EnrichmentStatus.PENDING.name)
        createTranscode(titleId = playable.id!!,
            filePath = "/nas/family/ripped.mkv")
        val unripped = createTitle(name = "Not Ripped",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = net.stewart.mediamanager.entity.EnrichmentStatus.PENDING.name)
        createTranscode(titleId = unripped.id!!,
            filePath = "/nas/family/notripped.mkv")  // ForBrowser MP4 not seeded

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val all = stub.listFamilyVideos(listFamilyVideosRequest { })
            assertEquals(2, all.videosCount, "without playableOnly both surface")

            val playableOnly = stub.listFamilyVideos(listFamilyVideosRequest {
                this.playableOnly = true
            })
            assertEquals(listOf("Ripped"),
                playableOnly.videosList.map { it.titleName })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getActorDetail ----------------------

    @Test
    fun `getActorDetail surfaces only playable owned titles for an actor`() = runBlocking {
        val viewer = createViewerUser(username = "ad-playable")
        // Two movies with the same actor; only one is playable on disk.
        val (rippedTitle, _) = seedPlayableMovie(name = "Ripped Movie",
            path = "/nas/movies/ripped.mp4")
        val unripped = createTitle(name = "Unripped Movie",
            mediaType = MediaTypeEntity.MOVIE.name)
        createTranscode(titleId = unripped.id!!,
            filePath = "/nas/movies/missing.mp4")
        // CastMember rows on both titles for the same person.
        CastMember(title_id = rippedTitle.id!!, tmdb_person_id = 287,
            name = "Brad Pitt", popularity = 90.0,
            character_name = "Tyler Durden",
            cast_order = 1).save()
        CastMember(title_id = unripped.id!!, tmdb_person_id = 287,
            name = "Brad Pitt", popularity = 90.0,
            character_name = "Aldo Raine",
            cast_order = 1).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getActorDetail(actorIdRequest { tmdbPersonId = 287 })
            assertEquals("Brad Pitt", resp.name)
            assertEquals(1, resp.ownedTitlesCount,
                "only the playable title appears in owned credits")
            assertEquals(rippedTitle.id!!,
                resp.ownedTitlesList.single().title.id)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listCollections ----------------------

    @Test
    fun `listCollections returns each TmdbCollection that has at least one playable owned title`() = runBlocking {
        val viewer = createViewerUser(username = "lc-ok")
        val coll = net.stewart.mediamanager.entity.TmdbCollection(
            tmdb_collection_id = 645,
            name = "Bond",
        ).apply { save() }
        val (title, _) = seedPlayableMovie(name = "Goldfinger",
            path = "/nas/movies/goldfinger.mp4")
        title.tmdb_collection_id = 645
        title.save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listCollections(Empty.getDefaultInstance())
            val hit = resp.collectionsList.single { it.name == "Bond" }
            assertEquals(645, hit.tmdbCollectionId)
            assertEquals(1, hit.titleCount,
                "owned-title count drives the left half of the badge")
        } finally {
            authed.shutdownNow()
        }
    }
}
