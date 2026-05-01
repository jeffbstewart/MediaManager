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
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.junit.Before
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [CatalogGrpcService.getTitleDetail] — the per-title
 * detail RPC that branches by media_type into buildAlbumDetail,
 * buildBookDetail, the seasons/episodes block, the family-members
 * block, and the formats/admin/readableEditions sections. Each test
 * walks one branch.
 *
 * Video-on-disk paths (transcodes that need a real file to be
 * "playable") stay out of scope; the tests below verify the metadata
 * shape, the per-media-type substructures, and the gating filters.
 */
class CatalogGrpcServiceTitleDetailTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()
        BookSeries.deleteAll()
        TmdbCollection.deleteAll()
    }

    // ---------------------- gating ----------------------

    @Test
    fun `getTitleDetail returns NOT_FOUND for unknown title id`() = runBlocking {
        val viewer = createViewerUser(username = "td-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getTitleDetail(titleIdRequest { titleId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTitleDetail returns NOT_FOUND for a hidden title`() = runBlocking {
        val viewer = createViewerUser(username = "td-hidden")
        val title = createTitle(name = "Hidden",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            hidden = true; save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTitleDetail returns NOT_FOUND for a title above the rating ceiling`() = runBlocking {
        val viewer = createViewerUser(username = "td-rating").apply {
            rating_ceiling = 1  // G only
            save()
        }
        val title = createTitle(name = "R rated",
            mediaType = MediaTypeEntity.BOOK.name,
            contentRating = "R")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- BOOK branch ----------------------

    @Test
    fun `getTitleDetail for a BOOK fills authors, editions, series ref, and the readable editions`() = runBlocking {
        val viewer = createViewerUser(username = "td-book")
        val author = Author(name = "Isaac Asimov",
            sort_name = "Asimov, Isaac",
            open_library_author_id = "OL34184A").apply { save() }
        val series = BookSeries(name = "Foundation",
            author_id = author.id).apply { save() }
        val book = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id
            series_number = BigDecimal("1")
            page_count = 244
            first_publication_year = 1951
            open_library_work_id = "OL46125W"
            save()
        }
        TitleAuthor(title_id = book.id!!, author_id = author.id!!,
            author_order = 0).save()
        // Two MediaItem editions (paperback + ebook); only the ebook is
        // a "readable edition" (file_path + EBOOK_EPUB).
        val paperback = MediaItem(product_name = "Paperback",
            media_format = MediaFormat.MASS_MARKET_PAPERBACK.name).apply { save() }
        MediaItemTitle(media_item_id = paperback.id!!,
            title_id = book.id!!, disc_number = 1).save()
        val ebook = MediaItem(product_name = "EPUB",
            media_format = MediaFormat.EBOOK_EPUB.name,
            file_path = "/library/foundation.epub").apply { save() }
        MediaItemTitle(media_item_id = ebook.id!!,
            title_id = book.id!!, disc_number = 2).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = book.id!! })
            assertTrue(resp.hasBook(), "BOOK detail block populated")
            assertEquals(244, resp.book.pageCount)
            assertEquals(1951, resp.book.firstPublicationYear)
            assertEquals("OL46125W", resp.book.openLibraryWorkId)
            assertEquals(1, resp.book.authorsCount)
            assertEquals("Isaac Asimov", resp.book.authorsList.single().name)
            assertEquals("Foundation", resp.book.bookSeries.name)
            assertEquals("1.00", resp.book.bookSeries.number)
            assertEquals(2, resp.book.editionsCount,
                "every linked MediaItem becomes an edition")
            // Only the EPUB shows up as a readable edition.
            assertEquals(1, resp.readableEditionsCount)
            assertEquals(MediaFormat.EBOOK_EPUB.name,
                resp.readableEditionsList.single().mediaFormat
                    .name.removePrefix("MEDIA_FORMAT_"))
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- ALBUM branch ----------------------

    @Test
    fun `getTitleDetail for an ALBUM fills the album, tracks, and album-artists`() = runBlocking {
        val viewer = createViewerUser(username = "td-album")
        val artist = Artist(name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name).apply { save() }
        val album = createTitle(name = "Animals",
            mediaType = MediaTypeEntity.ALBUM.name).apply {
            track_count = 2
            total_duration_seconds = 600
            label = "Harvest"
            save()
        }
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Pigs on the Wing").save()
        Track(title_id = album.id!!, track_number = 2, disc_number = 1,
            name = "Dogs").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = album.id!! })
            assertTrue(resp.hasAlbum())
            assertEquals(2, resp.album.tracksCount)
            assertEquals("Pigs on the Wing", resp.album.tracksList[0].name)
            assertEquals("Dogs", resp.album.tracksList[1].name)
            assertEquals(1, resp.album.albumArtistsCount)
            assertEquals("Pink Floyd", resp.album.albumArtistsList.single().name)
            assertEquals("Harvest", resp.album.label)
            assertEquals(2, resp.album.trackCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- TV branch ----------------------

    @Test
    fun `getTitleDetail for a TV title returns seasons sorted asc`() = runBlocking {
        val viewer = createViewerUser(username = "td-tv")
        val series = createTitle(name = "TV Show",
            mediaType = MediaTypeEntity.TV.name)
        // Insert season 2 before season 1 to verify the sort.
        TitleSeason(title_id = series.id!!, season_number = 2,
            acquisition_status = "OWNED").save()
        TitleSeason(title_id = series.id!!, season_number = 1,
            acquisition_status = "OWNED").save()
        // Season 0 (specials) must be excluded by season_number > 0.
        TitleSeason(title_id = series.id!!, season_number = 0,
            acquisition_status = "OWNED").save()
        // Two episodes for episodesList.
        Episode(title_id = series.id!!, season_number = 1, episode_number = 1,
            name = "Pilot", tmdb_id = 1001).save()
        Episode(title_id = series.id!!, season_number = 1, episode_number = 2,
            name = "Setup", tmdb_id = 1002).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = series.id!! })
            assertEquals(listOf(1, 2), resp.seasonsList.map { it.seasonNumber },
                "season 0 filtered out; rest sorted ascending")
            assertEquals(2, resp.episodesCount)
            assertEquals("Pilot", resp.episodesList[0].name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- PERSONAL branch ----------------------

    @Test
    fun `getTitleDetail for a PERSONAL title attaches family members`() = runBlocking {
        val viewer = createViewerUser(username = "td-personal")
        val title = createTitle(name = "Family Vacation 2024",
            mediaType = MediaTypeEntity.PERSONAL.name,
            // PERSONAL titles bypass the ENRICHED filter in loadCatalog.
            enrichmentStatus = EnrichmentStatus.PENDING.name)
        val mom = FamilyMember(name = "Mom").apply { save() }
        val dad = FamilyMember(name = "Dad").apply { save() }
        TitleFamilyMember(title_id = title.id!!, family_member_id = mom.id!!).save()
        TitleFamilyMember(title_id = title.id!!, family_member_id = dad.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            val names = resp.familyMembersFullList.map { it.name }.toSet()
            assertEquals(setOf("Mom", "Dad"), names)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- collection / formats / admin items ----------------------

    @Test
    fun `getTitleDetail attaches the TmdbCollection ref when the title belongs to one`() = runBlocking {
        val viewer = createViewerUser(username = "td-collection")
        TmdbCollection(tmdb_collection_id = 645,
            name = "James Bond Collection").save()
        val title = createTitle(name = "Goldfinger",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            tmdb_collection_id = 645
            save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            assertTrue(resp.hasCollection())
            assertEquals("James Bond Collection", resp.collection.name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getTitleDetail surfaces displayFormats from linked MediaItems and admin items only for admins`() = runBlocking {
        val viewer = createViewerUser(username = "td-formats-viewer")
        val admin = createAdminUser(username = "td-formats-admin")
        val title = createTitle(name = "Two Editions",
            mediaType = MediaTypeEntity.BOOK.name)
        val hardcover = MediaItem(product_name = "HC",
            media_format = MediaFormat.HARDBACK.name,
            upc = "9780000000001").apply { save() }
        MediaItemTitle(media_item_id = hardcover.id!!,
            title_id = title.id!!, disc_number = 1).save()
        // UNKNOWN format must be filtered out.
        val unknown = MediaItem(product_name = "?",
            media_format = MediaFormat.UNKNOWN.name).apply { save() }
        MediaItemTitle(media_item_id = unknown.id!!,
            title_id = title.id!!, disc_number = 2).save()

        val viewerChannel = authenticatedChannel(viewer)
        val adminChannel = authenticatedChannel(admin)
        try {
            val viewerStub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(viewerChannel)
            val viewerResp = viewerStub.getTitleDetail(
                titleIdRequest { titleId = title.id!! })
            assertEquals(1, viewerResp.displayFormatsCount,
                "only HARDBACK survives — UNKNOWN dropped")
            assertEquals(0, viewerResp.adminMediaItemsCount,
                "viewers don't see admin_media_items")

            val adminStub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(adminChannel)
            val adminResp = adminStub.getTitleDetail(
                titleIdRequest { titleId = title.id!! })
            assertEquals(2, adminResp.adminMediaItemsCount,
                "admins see every linked MediaItem (including UNKNOWN)")
        } finally {
            viewerChannel.shutdownNow()
            adminChannel.shutdownNow()
        }
    }

    // ---------------------- user-state flags ----------------------

    @Test
    fun `getTitleDetail reports favorite, hidden, and wished flags`() = runBlocking {
        val viewer = createViewerUser(username = "td-flags")
        val title = createTitle(name = "Flagged",
            mediaType = MediaTypeEntity.BOOK.name,
            tmdbId = 12345)
        UserTitleFlag(user_id = viewer.id!!, title_id = title.id!!,
            flag = UserFlagType.STARRED.name).save()
        UserTitleFlag(user_id = viewer.id!!, title_id = title.id!!,
            flag = UserFlagType.HIDDEN.name).save()
        // Active wish for the same tmdb_id+media_type.
        WishListItem(user_id = viewer.id!!,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 12345,
            tmdb_media_type = MediaTypeEntity.BOOK.name,
            tmdb_title = "Flagged",
            created_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            assertTrue(resp.isFavorite)
            assertTrue(resp.isHidden)
            assertTrue(resp.wished)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- cast / genres / tags ----------------------

    @Test
    fun `getTitleDetail returns cast sorted by cast_order, plus linked genres and tags`() = runBlocking {
        val viewer = createViewerUser(username = "td-aux")
        val title = createTitle(name = "Cast And Tags",
            mediaType = MediaTypeEntity.BOOK.name)
        // Insert in reverse order to verify sort.
        CastMember(title_id = title.id!!, tmdb_person_id = 2, name = "Second",
            cast_order = 2).save()
        CastMember(title_id = title.id!!, tmdb_person_id = 1, name = "First",
            cast_order = 1).save()
        val genre = Genre(name = "Sci-Fi").apply { save() }
        TitleGenre(title_id = title.id!!, genre_id = genre.id!!).save()
        val tag = Tag(name = "Classic", bg_color = "#aabbcc").apply { save() }
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.getTitleDetail(titleIdRequest { titleId = title.id!! })
            assertEquals(listOf("First", "Second"), resp.castList.map { it.name })
            assertEquals("Sci-Fi", resp.genresList.single().name)
            assertEquals("Classic", resp.tagsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }
}
