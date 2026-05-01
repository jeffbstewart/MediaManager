package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.junit.Before
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the smaller-but-still-substantial detail RPCs on
 * [CatalogGrpcService]: getActorDetail, getCollectionDetail,
 * getBookSeriesDetail, listFamilyVideos, plus the track admin RPCs
 * setTrackMusicTags + searchTracks.
 */
class CatalogGrpcServiceDetailRpcsTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        TrackTag.deleteAll()
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()
        BookSeries.deleteAll()
        // FK: TmdbCollectionPart references TmdbCollection.
        TmdbCollectionPart.deleteAll()
        TmdbCollection.deleteAll()
    }

    // ---------------------- getActorDetail ----------------------

    @Test
    fun `getActorDetail returns NOT_FOUND when no CastMember matches the tmdb_person_id`() = runBlocking {
        val viewer = createViewerUser(username = "actor-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getActorDetail(actorIdRequest { tmdbPersonId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getActorDetail returns the actor name and a headshot URL via the most popular cast member`() = runBlocking {
        val viewer = createViewerUser(username = "actor-ok")
        // Two cast-member rows for the same person on different titles;
        // higher popularity wins for the headshot.
        val titleA = createTitle(name = "Movie A",
            mediaType = MediaTypeEntity.BOOK.name)
        val titleB = createTitle(name = "Movie B",
            mediaType = MediaTypeEntity.BOOK.name)
        val low = CastMember(title_id = titleA.id!!, tmdb_person_id = 287,
            name = "Brad Pitt", popularity = 10.0,
            profile_path = "/low.jpg", cast_order = 1).apply { save() }
        val high = CastMember(title_id = titleB.id!!, tmdb_person_id = 287,
            name = "Brad Pitt", popularity = 100.0,
            profile_path = "/high.jpg", cast_order = 1).apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getActorDetail(actorIdRequest { tmdbPersonId = 287 })
            // Without a TMDB API key configured, fetchPersonDetails returns
            // found=false and the RPC falls back to the cast-member name.
            assertEquals("Brad Pitt", resp.name)
            assertEquals("/headshots/${high.id}", resp.headshotUrl,
                "highest-popularity profile_path wins")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getCollectionDetail ----------------------

    @Test
    fun `getCollectionDetail returns NOT_FOUND for an unknown tmdb_collection_id`() = runBlocking {
        val viewer = createViewerUser(username = "coll-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getCollectionDetail(collectionIdRequest {
                    tmdbCollectionId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getCollectionDetail walks TmdbCollectionPart rows in position order`() = runBlocking {
        val viewer = createViewerUser(username = "coll-ok")
        val coll = TmdbCollection(tmdb_collection_id = 645,
            name = "James Bond Collection",
            poster_path = "/jb.jpg").apply { save() }
        // Insert parts out of order to verify the position sort.
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 707,
            title = "Dr. No", position = 1, release_date = "1962-10-05",
            poster_path = "/dn.jpg").save()
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 661,
            title = "From Russia with Love", position = 2,
            release_date = "1963-10-10", poster_path = "/frwl.jpg").save()
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 660,
            title = "Goldfinger", position = 3,
            release_date = "1964-09-17", poster_path = "/gf.jpg").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getCollectionDetail(collectionIdRequest {
                tmdbCollectionId = 645
            })
            assertEquals("James Bond Collection", resp.name)
            assertEquals(3, resp.itemsCount)
            assertEquals(listOf("Dr. No", "From Russia with Love", "Goldfinger"),
                resp.itemsList.map { it.name })
            // None of the parts have local Title rows → none owned, none playable.
            assertTrue(resp.itemsList.none { it.owned })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getBookSeriesDetail ----------------------

    @Test
    fun `getBookSeriesDetail returns NOT_FOUND for an unknown series id`() = runBlocking {
        val viewer = createViewerUser(username = "bs-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getBookSeriesDetail(bookSeriesIdRequest { seriesId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getBookSeriesDetail surfaces volumes sorted by series_number when no OL author is set`() = runBlocking {
        val viewer = createViewerUser(username = "bs-ok")
        // Author with no open_library_author_id → can_fill_gaps=false →
        // the OpenLibrary fetch is skipped and missingVolumes stays empty.
        val author = Author(name = "Asimov", sort_name = "Asimov, Isaac",
            open_library_author_id = null).apply { save() }
        val series = BookSeries(name = "Foundation",
            author_id = author.id).apply { save() }
        // Insert volumes out of order to verify sort.
        val vol2 = createTitle(name = "Foundation and Empire",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id
            series_number = BigDecimal(2)
            save()
        }
        val vol1 = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id
            series_number = BigDecimal(1)
            save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getBookSeriesDetail(bookSeriesIdRequest {
                seriesId = series.id!!
            })
            assertEquals("Foundation", resp.name)
            assertEquals(listOf("Foundation", "Foundation and Empire"),
                resp.volumesList.map { it.titleName },
                "volumes sorted by series_number ascending")
            assertEquals(0, resp.missingVolumesCount,
                "no OL author id → no missing-volumes fetch")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listFamilyVideos ----------------------

    @Test
    fun `listFamilyVideos returns PERSONAL titles sorted newest event first by default`() = runBlocking {
        val viewer = createViewerUser(username = "fv-default")
        val older = createTitle(name = "Vacation 2023",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name).apply {
            event_date = java.time.LocalDate.of(2023, 6, 1)
            save()
        }
        val newer = createTitle(name = "Vacation 2024",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name).apply {
            event_date = java.time.LocalDate.of(2024, 6, 1)
            save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listFamilyVideos(listFamilyVideosRequest {})
            assertEquals(listOf("Vacation 2024", "Vacation 2023"),
                resp.videosList.map { it.titleName },
                "default sort = newest event_date first")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listFamilyVideos honours the family-member filter`() = runBlocking {
        val viewer = createViewerUser(username = "fv-member-filter")
        val mom = FamilyMember(name = "Mom").apply { save() }
        val dad = FamilyMember(name = "Dad").apply { save() }
        val withMom = createTitle(name = "With Mom",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        val withDad = createTitle(name = "With Dad",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        TitleFamilyMember(title_id = withMom.id!!, family_member_id = mom.id!!).save()
        TitleFamilyMember(title_id = withDad.id!!, family_member_id = dad.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listFamilyVideos(listFamilyVideosRequest {
                members.add(mom.id!!)
            })
            assertEquals(listOf("With Mom"), resp.videosList.map { it.titleName })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listFamilyVideos sort by name normalises case via sort_name`() = runBlocking {
        val viewer = createViewerUser(username = "fv-sort-name")
        createTitle(name = "Zebra Trip",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        createTitle(name = "Apple Picking",
            mediaType = MediaTypeEntity.PERSONAL.name,
            enrichmentStatus = EnrichmentStatus.PENDING.name)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listFamilyVideos(listFamilyVideosRequest {
                sort = FamilyVideoSort.FAMILY_VIDEO_SORT_NAME
            })
            assertEquals(listOf("Apple Picking", "Zebra Trip"),
                resp.videosList.map { it.titleName })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- searchTracks ----------------------

    @Test
    fun `searchTracks routes through TrackSearchService and returns a wired-up response`() = runBlocking {
        val viewer = createViewerUser(username = "st-q")
        val artist = Artist(name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val album = createTitle(name = "Animals",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Pigs on the Wing", duration_seconds = 600,
            bpm = 120, time_signature = "4/4").apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.searchTracks(searchTracksRequest { query = "pigs" })
            val hit = resp.tracksList.single()
            assertEquals("Pigs on the Wing", hit.name)
            assertEquals("Animals", hit.albumName)
            assertEquals("Pink Floyd", hit.artistName)
            assertEquals(120, hit.bpm)
            assertEquals("4/4", hit.timeSignature)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setTrackMusicTags ----------------------

    @Test
    fun `setTrackMusicTags requires admin — viewer gets PERMISSION_DENIED`() = runBlocking {
        val viewer = createViewerUser(username = "stmt-viewer")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T").apply { save() }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setTrackMusicTags(setTrackMusicTagsRequest {
                    trackId = track.id!!
                    bpm = 120
                })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setTrackMusicTags returns NOT_FOUND for unknown track id`() = runBlocking {
        val admin = createAdminUser(username = "stmt-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setTrackMusicTags(setTrackMusicTagsRequest {
                    trackId = 999_999
                    bpm = 120
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setTrackMusicTags rejects out-of-range bpm and malformed time_signature`() = runBlocking {
        val admin = createAdminUser(username = "stmt-bad")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val badBpm = assertFailsWith<StatusException> {
                stub.setTrackMusicTags(setTrackMusicTagsRequest {
                    trackId = track.id!!
                    bpm = 0
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, badBpm.status.code)

            val badSig = assertFailsWith<StatusException> {
                stub.setTrackMusicTags(setTrackMusicTagsRequest {
                    trackId = track.id!!
                    timeSignature = "weird"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, badSig.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setTrackMusicTags writes bpm + time_signature, then a clear nulls them`() = runBlocking {
        val admin = createAdminUser(username = "stmt-roundtrip")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setTrackMusicTags(setTrackMusicTagsRequest {
                trackId = track.id!!
                bpm = 140
                timeSignature = "3/4"
            })
            val written = Track.findById(track.id!!)!!
            assertEquals(140, written.bpm)
            assertEquals("3/4", written.time_signature)

            // Clear path nulls both fields out.
            stub.setTrackMusicTags(setTrackMusicTagsRequest {
                trackId = track.id!!
                clearBpm = true
                clearTimeSignature = true
            })
            val cleared = Track.findById(track.id!!)!!
            assertNull(cleared.bpm)
            assertNull(cleared.time_signature)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setTrackMusicTags purges stale BPM_BUCKET and TIME_SIG TrackTag rows`() = runBlocking {
        val admin = createAdminUser(username = "stmt-purge")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T", bpm = 80,
            time_signature = "4/4").apply { save() }
        // Pre-seed two auto-tags that the override should sweep away.
        val bpmTag = Tag(name = "80-99 BPM", bg_color = "#ff0000",
            source_type = TagSourceType.BPM_BUCKET.name).apply { save() }
        val sigTag = Tag(name = "4/4", bg_color = "#00ff00",
            source_type = TagSourceType.TIME_SIG.name).apply { save() }
        val manualTag = Tag(name = "Manual", bg_color = "#0000ff",
            source_type = TagSourceType.MANUAL.name).apply { save() }
        TrackTag(track_id = track.id!!, tag_id = bpmTag.id!!).save()
        TrackTag(track_id = track.id!!, tag_id = sigTag.id!!).save()
        TrackTag(track_id = track.id!!, tag_id = manualTag.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            stub.setTrackMusicTags(setTrackMusicTagsRequest {
                trackId = track.id!!
                clearBpm = true
                clearTimeSignature = true
            })
            val remaining = TrackTag.findAll().filter { it.track_id == track.id }
                .map { it.tag_id }
                .toSet()
            // Pre-seeded BPM_BUCKET / TIME_SIG rows are gone; the manual
            // tag survives. AutoTagApplicator may re-attach derived tags
            // from other sources (year etc.), which we don't lock down here.
            assertTrue(bpmTag.id!! !in remaining,
                "BPM_BUCKET auto-tag should have been swept")
            assertTrue(sigTag.id!! !in remaining,
                "TIME_SIG auto-tag should have been swept")
            assertTrue(manualTag.id!! in remaining,
                "manual tag should survive the purge")
        } finally {
            authed.shutdownNow()
        }
    }
}
