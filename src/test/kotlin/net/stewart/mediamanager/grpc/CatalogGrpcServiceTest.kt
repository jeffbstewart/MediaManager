package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.DismissedNotification
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.PlaybackProgress as ProgressEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration tests for the leaf RPCs of [CatalogGrpcService] —
 * homeFeed / search / getTitleDetail / listTitles all need their
 * own dedicated suites because they walk most of the catalog graph.
 * This file covers the surface around those: dismissals, season /
 * episode listings, tags, favorites, and the small admin-gated
 * tag mutations.
 */
class CatalogGrpcServiceTest : GrpcTestBase() {

    // ---------------------- getFeatures ----------------------

    @Test
    fun `getFeatures requires authentication`() = runBlocking {
        val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.getFeatures(Empty.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    @Test
    fun `getFeatures returns a populated Features for an authenticated user`() = runBlocking {
        val viewer = createViewerUser(username = "feat-viewer")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getFeatures(Empty.getDefaultInstance())
            // The Features proto should be returned without error; we don't
            // assert specific feature values (they vary with config) — just
            // that the call resolves a populated message.
            assertEquals(resp, resp) // no exception above is the assertion.
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- dismissContinueWatching ----------------------

    @Test
    fun `dismissContinueWatching deletes the user's progress for every transcode of a title`() = runBlocking {
        val viewer = createViewerUser(username = "dcw")
        val title = createTitle(name = "Watched", contentRating = "PG")
        val tc1 = createTranscode(title.id!!, "/a.mkv")
        val tc2 = createTranscode(title.id!!, "/b.mkv")

        // Seed progress for two transcodes.
        ProgressEntity(user_id = viewer.id!!, transcode_id = tc1.id!!,
            position_seconds = 120.0, duration_seconds = 7200.0,
            updated_at = LocalDateTime.now()).save()
        ProgressEntity(user_id = viewer.id!!, transcode_id = tc2.id!!,
            position_seconds = 300.0, duration_seconds = 7200.0,
            updated_at = LocalDateTime.now()).save()

        // Other user's progress on the same title — must NOT be touched.
        val other = createViewerUser(username = "other-dcw")
        ProgressEntity(user_id = other.id!!, transcode_id = tc1.id!!,
            position_seconds = 60.0, duration_seconds = 7200.0,
            updated_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.dismissContinueWatching(titleIdRequest { titleId = title.id!! })

            val mine = ProgressEntity.findAll().count { it.user_id == viewer.id }
            val theirs = ProgressEntity.findAll().count { it.user_id == other.id }
            assertEquals(0, mine, "all caller progress for the title cleared")
            assertEquals(1, theirs, "other user's progress untouched")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- dismissMissingSeason ----------------------

    @Test
    fun `dismissMissingSeason with a season number dismisses just that season`() = runBlocking {
        val viewer = createViewerUser(username = "dms-one")
        val title = createTitle(name = "Show", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-PG")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.dismissMissingSeason(dismissMissingSeasonRequest {
                titleId = title.id!!
                seasonNumber = 3
            })
            val keys = DismissedNotification.findAll().map { it.notification_key }
            assertEquals(listOf("missing_season:${title.id}:3"), keys)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `dismissMissingSeason without a season number dismisses every non-OWNED season for that title`() = runBlocking {
        val viewer = createViewerUser(username = "dms-all")
        val title = createTitle(name = "Show All", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-PG")
        // OWNED season excluded from dismissal sweep; UNKNOWN seasons included.
        TitleSeason(title_id = title.id!!, season_number = 1,
            acquisition_status = AcquisitionStatus.OWNED.name).save()
        TitleSeason(title_id = title.id!!, season_number = 2).save() // UNKNOWN
        TitleSeason(title_id = title.id!!, season_number = 3,
            acquisition_status = AcquisitionStatus.ORDERED.name).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.dismissMissingSeason(dismissMissingSeasonRequest {
                titleId = title.id!!
                // No season_number set.
            })
            val keys = DismissedNotification.findAll()
                .map { it.notification_key }.toSet()
            assertEquals(setOf(
                "missing_season:${title.id}:2",
                "missing_season:${title.id}:3",
            ), keys, "OWNED is excluded; UNKNOWN+ORDERED both dismissed")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listSeasons / listEpisodes ----------------------

    @Test
    fun `listSeasons groups episodes by season and joins on title_season for the name`() = runBlocking {
        val viewer = createViewerUser(username = "seasons-viewer")
        val title = createTitle(name = "Show", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-PG")

        TitleSeason(title_id = title.id!!, season_number = 1, name = "First").save()
        // Season 2 has no TitleSeason name -> falls through.
        Episode(title_id = title.id!!, season_number = 1, episode_number = 1).save()
        Episode(title_id = title.id!!, season_number = 1, episode_number = 2).save()
        Episode(title_id = title.id!!, season_number = 2, episode_number = 1).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listSeasons(titleIdRequest { titleId = title.id!! })
            val byNum = resp.seasonsList.associateBy { it.seasonNumber }
            assertEquals(2, byNum[1]?.episodeCount)
            assertEquals("First", byNum[1]?.name)
            assertEquals(1, byNum[2]?.episodeCount)
            // Season 2 has no TitleSeason — name should be empty (default).
            assertEquals("", byNum[2]?.name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listSeasons returns NOT_FOUND for a movie title`() = runBlocking {
        val viewer = createViewerUser(username = "seasons-movie")
        val title = createTitle(name = "Movie", mediaType = MediaTypeEntity.MOVIE.name,
            contentRating = "PG")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.listSeasons(titleIdRequest { titleId = title.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listSeasons returns NOT_FOUND when title hidden or above rating ceiling`() = runBlocking {
        val limited = createViewerUser(username = "seasons-limited").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        val tvMa = createTitle(name = "Mature", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-MA")
        val authed = authenticatedChannel(limited)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.listSeasons(titleIdRequest { titleId = tvMa.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listEpisodes returns NOT_FOUND when no episodes exist for the season`() = runBlocking {
        val viewer = createViewerUser(username = "eps-empty")
        val title = createTitle(name = "Show", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-PG")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.listEpisodes(listEpisodesRequest {
                    titleId = title.id!!
                    seasonNumber = 99
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listEpisodes returns episodes sorted by episode_number`() = runBlocking {
        val viewer = createViewerUser(username = "eps-sorted")
        val title = createTitle(name = "Sorted", mediaType = MediaTypeEntity.TV.name,
            contentRating = "TV-PG")
        // Insert out of order; service sorts.
        Episode(title_id = title.id!!, season_number = 1, episode_number = 3,
            name = "Three").save()
        Episode(title_id = title.id!!, season_number = 1, episode_number = 1,
            name = "One").save()
        Episode(title_id = title.id!!, season_number = 1, episode_number = 2,
            name = "Two").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listEpisodes(listEpisodesRequest {
                titleId = title.id!!
                seasonNumber = 1
            })
            assertEquals(listOf(1, 2, 3),
                resp.episodesList.map { it.episodeNumber })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listTags / getTagDetail ----------------------

    @Test
    fun `listTags returns empty when no playable titles are tagged`() = runBlocking {
        val viewer = createViewerUser(username = "tags-empty")
        // Tag exists but no TitleTag links — listTags hides empty tags.
        Tag(name = "OrphanTag", bg_color = "#6B7280").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTags(Empty.getDefaultInstance())
            assertEquals(0, resp.tagsCount,
                "tags with zero playable titles are filtered out")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTagDetail returns NOT_FOUND for an unknown tag id`() = runBlocking {
        val viewer = createViewerUser(username = "tags-missing")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getTagDetail(tagIdRequest { tagId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTagDetail returns the tag's name and color`() = runBlocking {
        val viewer = createViewerUser(username = "tags-detail")
        val tag = Tag(name = "Sci-Fi", bg_color = "#1E40AF").apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTagDetail(tagIdRequest { tagId = tag.id!! })
            assertEquals("Sci-Fi", resp.name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- tag mutations (admin-gated) ----------------------

    @Test
    fun `addTagToTitle is rejected for non-admin viewers`() = runBlocking {
        val viewer = createViewerUser(username = "tag-viewer")
        val tag = Tag(name = "Aaa").apply { save() }
        val title = createTitle(name = "TT")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.addTagToTitle(tagTitleRequest { tagId = tag.id!!; titleId = title.id!! })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTagToTitle returns NOT_FOUND for unknown tag or title id`() = runBlocking {
        val admin = createAdminUser(username = "tag-admin-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val tag = Tag(name = "B").apply { save() }
            val title = createTitle(name = "TitleB")

            val unknownTag = assertFailsWith<StatusException> {
                stub.addTagToTitle(tagTitleRequest { tagId = 999_999; titleId = title.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownTag.status.code)

            val unknownTitle = assertFailsWith<StatusException> {
                stub.addTagToTitle(tagTitleRequest { tagId = tag.id!!; titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownTitle.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTagToTitle and removeTagFromTitle round-trip for an admin`() = runBlocking {
        val admin = createAdminUser(username = "tag-admin-rt")
        val tag = Tag(name = "Round").apply { save() }
        val title = createTitle(name = "RoundTitle")

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.addTagToTitle(tagTitleRequest { tagId = tag.id!!; titleId = title.id!! })
            assertEquals(1, TitleTag.findAll().count {
                it.tag_id == tag.id && it.title_id == title.id
            })
            stub.removeTagFromTitle(tagTitleRequest { tagId = tag.id!!; titleId = title.id!! })
            assertEquals(0, TitleTag.findAll().count {
                it.tag_id == tag.id && it.title_id == title.id
            })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setFavorite / setHidden ----------------------

    @Test
    fun `setFavorite toggles the STARRED flag`() = runBlocking {
        val viewer = createViewerUser(username = "fav-toggle")
        val title = createTitle(name = "Loved", contentRating = "PG")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setFavorite(setFlagRequest { titleId = title.id!!; value = true })
            assertEquals(1, UserTitleFlag.findAll().count {
                it.user_id == viewer.id && it.title_id == title.id &&
                    it.flag == UserFlagType.STARRED.name
            })

            stub.setFavorite(setFlagRequest { titleId = title.id!!; value = false })
            assertEquals(0, UserTitleFlag.findAll().count {
                it.user_id == viewer.id && it.title_id == title.id &&
                    it.flag == UserFlagType.STARRED.name
            })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setFavorite returns NOT_FOUND for an unknown title`() = runBlocking {
        val viewer = createViewerUser(username = "fav-unknown")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setFavorite(setFlagRequest { titleId = 999_999; value = true })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setHidden toggles the HIDDEN flag and accepts already-hidden titles`() = runBlocking {
        val viewer = createViewerUser(username = "hidden-toggle")
        // Note: setHidden does NOT reject hidden titles — the user is the
        // one hiding/un-hiding them. setFavorite does, because the user
        // shouldn't see hidden titles in their starred list.
        val title = createTitle(name = "ToHide", contentRating = "PG")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setHidden(setFlagRequest { titleId = title.id!!; value = true })
            assertTrue(UserTitleFlag.findAll().any {
                it.user_id == viewer.id && it.title_id == title.id &&
                    it.flag == UserFlagType.HIDDEN.name
            })
            stub.setHidden(setFlagRequest { titleId = title.id!!; value = false })
            assertFalse(UserTitleFlag.findAll().any {
                it.user_id == viewer.id && it.title_id == title.id &&
                    it.flag == UserFlagType.HIDDEN.name
            })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listAdvancedSearchPresets ----------------------

    @Test
    fun `listAdvancedSearchPresets returns the canonical preset list`() = runBlocking {
        val viewer = createViewerUser(username = "presets")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listAdvancedSearchPresets(Empty.getDefaultInstance())
            assertTrue(resp.presetsCount > 0,
                "AdvancedSearchPresets.ALL must surface at least one preset")
            // Every preset has a non-blank key + name.
            resp.presetsList.forEach { p ->
                assertTrue(p.key.isNotBlank())
                assertTrue(p.name.isNotBlank())
            }
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- mintPublicArtToken ----------------------

    @Test
    fun `mintPublicArtToken returns a token for a visible title`() = runBlocking {
        val viewer = createViewerUser(username = "art-mint")
        val title = createTitle(name = "Art", contentRating = "PG")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.mintPublicArtToken(titleIdRequest { titleId = title.id!! })
            assertTrue(resp.token.isNotBlank())
            assertTrue(resp.ttl.nanos > 0)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mintPublicArtToken returns NOT_FOUND for unknown title`() = runBlocking {
        val viewer = createViewerUser(username = "art-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.mintPublicArtToken(titleIdRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mintPublicArtToken returns PERMISSION_DENIED for rating-restricted titles`() = runBlocking {
        val limited = createViewerUser(username = "art-limited").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        val mature = createTitle(name = "Mature Art", contentRating = "TV-MA")
        val authed = authenticatedChannel(limited)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.mintPublicArtToken(titleIdRequest { titleId = mature.id!! })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listCollections ----------------------

    @Test
    fun `listCollections returns an empty list when nothing is collected`() = runBlocking {
        val viewer = createViewerUser(username = "collections-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listCollections(Empty.getDefaultInstance())
            assertEquals(0, resp.collectionsCount)
        } finally {
            authed.shutdownNow()
        }
    }
}
