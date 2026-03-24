package net.stewart.mediamanager.grpc

import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AcquisitionStatus as AcquisitionStatusEntity
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.EntrySource
import net.stewart.mediamanager.entity.ExpansionStatus
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.WishLifecycleStage as WishLifecycleStageEntity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WishListLifecycleTest : GrpcTestBase() {

    @Test
    fun `list wishes is user scoped but keeps aggregate votes`() = runBlocking {
        val viewer = createViewerUser(username = "viewer1")
        val other = createViewerUser(username = "viewer2")
        val title = createTitle(name = "Scoped Movie", mediaType = MediaTypeEntity.MOVIE.name, tmdbId = 101)
        TitleSeason(title_id = title.id!!, season_number = 0).save()

        WishListService.addMediaWishForUser(viewer.id!!, TmdbId(101, MediaTypeEntity.MOVIE), "Scoped Movie", null, 2024, 50.0)
        WishListService.addMediaWishForUser(other.id!!, TmdbId(101, MediaTypeEntity.MOVIE), "Scoped Movie", null, 2024, 50.0)
        WishListService.addMediaWishForUser(other.id!!, TmdbId(102, MediaTypeEntity.MOVIE), "Other Movie", null, 2023, 10.0)

        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = WishListServiceGrpcKt.WishListServiceCoroutineStub(authedChannel)
            val response = stub.listWishes(Empty.getDefaultInstance())

            assertEquals(1, response.wishesCount)
            val wish = response.wishesList.first()
            assertEquals(2, wish.voteCount)
            assertTrue(wish.userVoted)
            assertEquals(net.stewart.mediamanager.grpc.WishLifecycleStage.WISH_LIFECYCLE_STAGE_WISHED_FOR, wish.lifecycleStage)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `lifecycle moves from ordered to in house to nas to ready`() {
        val user = createViewerUser(username = "owner")
        val title = createTitle(name = "Lifecycle Movie", mediaType = MediaTypeEntity.MOVIE.name, tmdbId = 201)
        val titleSeason = TitleSeason(
            title_id = title.id!!,
            season_number = 0,
            acquisition_status = AcquisitionStatusEntity.ORDERED.name
        )
        titleSeason.save()
        WishListService.addMediaWishForUser(user.id!!, TmdbId(201, MediaTypeEntity.MOVIE), "Lifecycle Movie", null, 2024, 10.0)

        assertEquals(WishLifecycleStageEntity.ORDERED, lifecycleFor(user.id!!, 201))
        assertEquals(0, WishListService.getReadyToWatchWishCountForUser(user.id!!))

        MediaItem(
            media_format = net.stewart.mediamanager.entity.MediaFormat.BLURAY.name,
            entry_source = EntrySource.MANUAL.name,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name
        ).apply {
            save()
            MediaItemTitle(media_item_id = id!!, title_id = title.id!!, disc_number = 1).save()
        }
        WishListService.syncPhysicalOwnership(title.id!!)
        assertEquals(WishLifecycleStageEntity.IN_HOUSE_PENDING_NAS, lifecycleFor(user.id!!, 201))

        val nasRoot = Files.createTempDirectory("wishlist-nas").toFile()
        AppConfig(config_key = "nas_root_path", config_val = nasRoot.absolutePath).save()
        val source = nasRoot.resolve("BLURAY/Lifecycle Movie.mkv").apply {
            parentFile.mkdirs()
            writeText("source")
        }
        createTranscode(titleId = title.id!!, filePath = source.absolutePath)
        assertEquals(WishLifecycleStageEntity.ON_NAS_PENDING_DESKTOP, lifecycleFor(user.id!!, 201))
        assertEquals(0, WishListService.getReadyToWatchWishCountForUser(user.id!!))

        val forBrowser = TranscoderAgent.getForBrowserPath(nasRoot.absolutePath, source.absolutePath)
        forBrowser.parentFile.mkdirs()
        forBrowser.writeText("browser")
        assertEquals(WishLifecycleStageEntity.READY_TO_WATCH, lifecycleFor(user.id!!, 201))
        assertEquals(1, WishListService.getReadyToWatchWishCountForUser(user.id!!))
    }

    @Test
    fun `priority counts include lifecycle driven backlog states`() {
        val user = createViewerUser(username = "priority")

        val ripTitle = createTitle(name = "Rip First", mediaType = MediaTypeEntity.MOVIE.name, tmdbId = 301)
        TitleSeason(title_id = ripTitle.id!!, season_number = 0, acquisition_status = AcquisitionStatusEntity.OWNED.name).save()
        WishListService.addMediaWishForUser(user.id!!, TmdbId(301, MediaTypeEntity.MOVIE), "Rip First", null, 2024, 50.0)

        val transcodeTitle = createTitle(name = "Transcode First", mediaType = MediaTypeEntity.MOVIE.name, tmdbId = 302)
        TitleSeason(title_id = transcodeTitle.id!!, season_number = 0, acquisition_status = AcquisitionStatusEntity.OWNED.name).save()
        WishListService.addMediaWishForUser(user.id!!, TmdbId(302, MediaTypeEntity.MOVIE), "Transcode First", null, 2024, 50.0)

        val nasRoot = Files.createTempDirectory("wishlist-priority").toFile()
        AppConfig(config_key = "nas_root_path", config_val = nasRoot.absolutePath).save()
        val source = nasRoot.resolve("BLURAY/Transcode First.mkv").apply {
            parentFile.mkdirs()
            writeText("source")
        }
        createTranscode(titleId = transcodeTitle.id!!, filePath = source.absolutePath)

        val ripCounts = WishListService.getRipPriorityCounts()
        val transcodeCounts = WishListService.getDesktopTranscodePriorityCounts()

        assertEquals(1, ripCounts[ripTitle.id])
        assertEquals(1, transcodeCounts[transcodeTitle.id])
    }

    @Test
    fun `admin purchase wish status update is season aware`() = runBlocking {
        val viewer = createViewerUser(username = "seasonviewer")
        val admin = createAdminUser(username = "seasonadmin")
        val show = createTitle(name = "Season Show", mediaType = MediaTypeEntity.TV.name, tmdbId = 401)
        TitleSeason(title_id = show.id!!, season_number = 1).save()
        TitleSeason(title_id = show.id!!, season_number = 2).save()

        WishListService.addMediaWishForUser(viewer.id!!, TmdbId(401, MediaTypeEntity.TV), "Season Show", null, 2024, 10.0, seasonNumber = 1)
        WishListService.addMediaWishForUser(viewer.id!!, TmdbId(401, MediaTypeEntity.TV), "Season Show", null, 2024, 10.0, seasonNumber = 2)

        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)
            stub.updatePurchaseWishStatus(updatePurchaseWishStatusRequest {
                tmdbId = 401
                mediaType = MediaType.MEDIA_TYPE_TV
                seasonNumber = 2
                status = AcquisitionStatus.ACQUISITION_STATUS_ORDERED
            })
        } finally {
            authedChannel.shutdownNow()
        }

        val season1 = TitleSeason.findAll().first { it.title_id == show.id && it.season_number == 1 }
        val season2 = TitleSeason.findAll().first { it.title_id == show.id && it.season_number == 2 }
        assertEquals(AcquisitionStatusEntity.UNKNOWN.name, season1.acquisition_status)
        assertEquals(AcquisitionStatusEntity.ORDERED.name, season2.acquisition_status)
    }

    private fun lifecycleFor(userId: Long, tmdbId: Int): WishLifecycleStageEntity {
        val summary = WishListService.getVisibleMediaWishSummariesForUser(userId)
            .firstOrNull { it.wish.tmdb_id == tmdbId }
        assertNotNull(summary)
        return summary.lifecycleStage
    }
}
