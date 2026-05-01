package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.SearchIndexService
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [CatalogGrpcService.search] — the cross-catalog text
 * search that fans out across titles, artists, authors, tracks, actors,
 * collections, tags, and genres. Each test seeds just enough catalog
 * graph to fire one branch of the search.
 *
 * The video-title branch is skipped here because it requires a
 * playable transcode (real on-disk file with a direct extension); the
 * book / album / artist / author / actor / collection / tag / genre
 * branches all run against ENRICHED titles without that constraint.
 */
class CatalogGrpcServiceSearchTest : GrpcTestBase() {

    @Before
    fun cleanCatalogTables() {
        // Base @Before doesn't clear these.
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Track.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()
        TmdbCollection.deleteAll()
    }

    private fun seedEnrichedTitle(
        name: String,
        mediaType: String = MediaTypeEntity.BOOK.name,
        popularity: Double? = 50.0,
        contentRating: String? = "G",
    ): Title {
        val t = createTitle(
            name = name,
            mediaType = mediaType,
            enrichmentStatus = EnrichmentStatus.ENRICHED.name,
            contentRating = contentRating,
            popularity = popularity,
            posterPath = "/p.jpg",
            releaseYear = 2020,
        )
        SearchIndexService.onTitleChanged(t.id!!)
        return t
    }

    // ---------------------- empty query ----------------------

    @Test
    fun `search with an empty query returns an empty SearchResponse`() = runBlocking {
        val viewer = createViewerUser(username = "search-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "  " })  // trims to empty
            assertEquals(0, resp.resultsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- title-by-name searches ----------------------

    @Test
    fun `search returns BOOK titles when include_books is set`() = runBlocking {
        val viewer = createViewerUser(username = "search-book")
        val title = seedEnrichedTitle("Foundation", MediaTypeEntity.BOOK.name)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            // Without include_books the BOOK branch is skipped.
            val without = stub.search(searchRequest { query = "foundation" })
            assertEquals(0, without.resultsCount,
                "BOOK results gated behind include_books")

            val withBooks = stub.search(searchRequest {
                query = "foundation"
                includeBooks = true
            })
            val bookHit = withBooks.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_BOOK
            }
            assertEquals("Foundation", bookHit.name)
            assertEquals(title.id!!, bookHit.titleId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search returns ALBUM titles with the album-artist name when include_audio is set`() = runBlocking {
        val viewer = createViewerUser(username = "search-album")
        val album = seedEnrichedTitle("The Wall", MediaTypeEntity.ALBUM.name)
        val artist = Artist(
            name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name,
        ).apply { save() }
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "wall"
                includeAudio = true
            })
            val albumHit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_ALBUM
            }
            assertEquals("The Wall", albumHit.name)
            assertEquals("Pink Floyd", albumHit.artistName)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- artist / track / author / actor ----------------------

    @Test
    fun `search returns ARTIST hits when an Artist owns at least one album`() = runBlocking {
        val viewer = createViewerUser(username = "search-artist")
        val album = seedEnrichedTitle("Wish You Were Here",
            MediaTypeEntity.ALBUM.name)
        val artist = Artist(
            name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name,
        ).apply { save() }
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "pink floyd"
                includeAudio = true
            })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_ARTIST
            }
            assertEquals("Pink Floyd", hit.name)
            assertEquals(1, hit.titleCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search returns TRACK hits with album context`() = runBlocking {
        val viewer = createViewerUser(username = "search-track")
        val album = seedEnrichedTitle("Animals", MediaTypeEntity.ALBUM.name)
        val artist = Artist(
            name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistType.GROUP.name,
        ).apply { save() }
        TitleArtist(title_id = album.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = album.id!!, track_number = 1, disc_number = 1,
            name = "Pigs on the Wing").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "pigs"
                includeAudio = true
            })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_TRACK
            }
            assertEquals("Pigs on the Wing", hit.name)
            assertEquals("Animals", hit.albumName)
            assertEquals("Pink Floyd", hit.artistName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search returns AUTHOR hits when an Author owns at least one book`() = runBlocking {
        val viewer = createViewerUser(username = "search-author")
        val book = seedEnrichedTitle("Foundation", MediaTypeEntity.BOOK.name)
        val author = Author(name = "Isaac Asimov",
            sort_name = "Asimov, Isaac").apply { save() }
        TitleAuthor(title_id = book.id!!, author_id = author.id!!,
            author_order = 0).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "asimov"
                includeBooks = true
            })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_AUTHOR
            }
            assertEquals("Isaac Asimov", hit.name)
            assertEquals(1, hit.titleCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search returns ACTOR hits when a CastMember matches a title in catalog`() = runBlocking {
        val viewer = createViewerUser(username = "search-actor")
        // Visible title (BOOK avoids the playable-transcode requirement
        // but the cast-member branch only checks catalog.titlesById).
        val movie = seedEnrichedTitle("Some Title", MediaTypeEntity.BOOK.name)
        CastMember(title_id = movie.id!!, tmdb_person_id = 287,
            name = "Brad Pitt", popularity = 90.0,
            profile_path = "/bp.jpg").save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "brad pitt" })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_ACTOR
            }
            assertEquals("Brad Pitt", hit.name)
            assertEquals(287, hit.tmdbPersonId)
            assertEquals(1, hit.titleCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- collection / tag / genre ----------------------

    @Test
    fun `search returns COLLECTION hits when a TmdbCollection name matches`() = runBlocking {
        val viewer = createViewerUser(username = "search-collection")
        TmdbCollection(tmdb_collection_id = 9485, name = "Fast and Furious",
            poster_path = "/ff.jpg").save()
        // Owned title in the collection so maxPop has something to read.
        seedEnrichedTitle("Some Movie", MediaTypeEntity.BOOK.name).apply {
            tmdb_collection_id = 9485; save()
        }

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "fast" })
            val hit = resp.resultsList.single {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_COLLECTION
            }
            assertEquals("Fast and Furious", hit.name)
            assertEquals(9485, hit.tmdbCollectionId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search includes TAG and GENRE hits keyed off the playable title set`() = runBlocking {
        val viewer = createViewerUser(username = "search-tag-genre")
        // BOOK titles count as catalog members (ENRICHED + visible) but
        // are NOT playable. The TAG/GENRE branches both walk
        // playableTitleIds, which excludes books — so we expect zero
        // matches even when the names align.
        val book = seedEnrichedTitle("Some Book", MediaTypeEntity.BOOK.name)
        val tag = Tag(name = "Mystery", bg_color = "#aabbcc").apply { save() }
        TitleTag(title_id = book.id!!, tag_id = tag.id!!).save()
        val genre = Genre(name = "Mystery").apply { save() }
        TitleGenre(title_id = book.id!!, genre_id = genre.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest { query = "mystery" })
            // Tag and genre only surface when at least one playable
            // (video) title is associated; BOOK-only owners skip both.
            assertEquals(0, resp.resultsList.count {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_TAG ||
                    it.resultType == SearchResultType.SEARCH_RESULT_TYPE_GENRE
            })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- ranking + filtering ----------------------

    @Test
    fun `search ranks more popular results before less popular ones`() = runBlocking {
        val viewer = createViewerUser(username = "search-rank")
        seedEnrichedTitle("Foundation Volume 1", MediaTypeEntity.BOOK.name,
            popularity = 10.0)
        seedEnrichedTitle("Foundation Volume 2", MediaTypeEntity.BOOK.name,
            popularity = 99.0)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "foundation"
                includeBooks = true
            })
            val books = resp.resultsList.filter {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_BOOK
            }
            assertEquals(2, books.size)
            assertEquals("Foundation Volume 2", books[0].name,
                "higher popularity ranks first")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `search excludes hidden and rating-restricted titles via loadCatalog`() = runBlocking {
        // Viewer with an MPA-G ceiling sees nothing rated higher than G,
        // and never sees hidden titles.
        val viewer = createViewerUser(username = "search-restricted").apply {
            rating_ceiling = 1  // G only
            save()
        }
        seedEnrichedTitle("Hidden Foundation", MediaTypeEntity.BOOK.name,
            contentRating = "G").apply { hidden = true; save() }
        seedEnrichedTitle("Foundation R-rated", MediaTypeEntity.BOOK.name,
            contentRating = "R")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.search(searchRequest {
                query = "foundation"
                includeBooks = true
            })
            assertTrue(resp.resultsList.none {
                it.resultType == SearchResultType.SEARCH_RESULT_TYPE_BOOK
            }, "neither hidden nor R-rated books should leak")
        } finally {
            authed.shutdownNow()
        }
    }
}
