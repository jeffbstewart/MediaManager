package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused tests for [CatalogGrpcService.listTitles] — the workhorse
 * paginated catalog query. Filters: search, type, genre, tag, rating,
 * playable_only. Sorts: popularity (default), name, year, recent.
 */
class CatalogGrpcServiceListTitlesTest : GrpcTestBase() {

    private fun stubFor(user: net.stewart.mediamanager.entity.AppUser) =
        CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authenticatedChannel(user))

    // ---------------------- pagination ----------------------

    @Test
    fun `listTitles paginates correctly with totalPages computed from total + limit`() = runBlocking {
        val viewer = createViewerUser(username = "list-page")
        for (i in 1..7) createTitle(name = "T$i", popularity = i.toDouble())

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val page1 = stub.listTitles(listTitlesRequest { page = 1; limit = 3 })
            assertEquals(7, page1.pagination.total)
            assertEquals(3, page1.pagination.totalPages, "ceil(7/3)")
            assertEquals(3, page1.titlesCount)

            val page3 = stub.listTitles(listTitlesRequest { page = 3; limit = 3 })
            assertEquals(1, page3.titlesCount, "page 3 has just the trailing remainder")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles clamps page to at least 1 and limit to a sane range`() = runBlocking {
        val viewer = createViewerUser(username = "list-clamp")
        createTitle(name = "Only One")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            // page=0 clamps to 1.
            val resp = stub.listTitles(listTitlesRequest { page = 0; limit = 5 })
            assertEquals(1, resp.pagination.page)
            // limit=0 falls back to the default page size.
            val zeroLimit = stub.listTitles(listTitlesRequest { page = 1 })
            assertTrue(zeroLimit.pagination.limit > 0)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- type filter ----------------------

    @Test
    fun `listTitles type filter narrows by MediaType`() = runBlocking {
        val viewer = createViewerUser(username = "list-type")
        createTitle(name = "Movie A", mediaType = MediaTypeEntity.MOVIE.name)
        createTitle(name = "Movie B", mediaType = MediaTypeEntity.MOVIE.name)
        createTitle(name = "Show A", mediaType = MediaTypeEntity.TV.name)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val movies = stub.listTitles(listTitlesRequest {
                type = MediaType.MEDIA_TYPE_MOVIE
            })
            assertEquals(2, movies.titlesCount)
            val tv = stub.listTitles(listTitlesRequest {
                type = MediaType.MEDIA_TYPE_TV
            })
            assertEquals(1, tv.titlesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- genre + tag filters ----------------------

    @Test
    fun `listTitles genre filter narrows to titles with the named genre`() = runBlocking {
        val viewer = createViewerUser(username = "list-genre")
        val sciFi = Genre(name = "Science Fiction").apply { save() }
        Genre(name = "Drama").apply { save() }
        val a = createTitle(name = "Alien")
        val b = createTitle(name = "The Wire")
        TitleGenre(title_id = a.id!!, genre_id = sciFi.id!!).save()
        // The Wire intentionally not tagged with Science Fiction.

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { genre = "Science Fiction" })
            assertEquals(1, resp.titlesCount)
            assertEquals("Alien", resp.titlesList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles genre filter on unknown genre returns empty`() = runBlocking {
        val viewer = createViewerUser(username = "list-genre-unknown")
        createTitle(name = "Anything")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { genre = "Nonexistent Genre" })
            assertEquals(0, resp.titlesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles tag filter narrows to titles with the named tag`() = runBlocking {
        val viewer = createViewerUser(username = "list-tag")
        val noir = Tag(name = "Noir").apply { save() }
        val a = createTitle(name = "Chinatown")
        createTitle(name = "Apollo 13")
        TitleTag(title_id = a.id!!, tag_id = noir.id!!).save()

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { tag = "Noir" })
            assertEquals(1, resp.titlesCount)
            assertEquals("Chinatown", resp.titlesList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles tag filter on unknown tag returns empty`() = runBlocking {
        val viewer = createViewerUser(username = "list-tag-unknown")
        createTitle(name = "Anything")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { tag = "Nonexistent Tag" })
            assertEquals(0, resp.titlesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- rating filter ----------------------

    @Test
    fun `listTitles rating filter restricts to the supplied rating set`() = runBlocking {
        val viewer = createViewerUser(username = "list-ratings")
        createTitle(name = "PG", contentRating = "PG")
        createTitle(name = "TV-MA", contentRating = "TV-MA")
        createTitle(name = "Untagged", contentRating = null)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest {
                ratings.add("PG")
            })
            assertEquals(1, resp.titlesCount)
            assertEquals("PG", resp.titlesList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles availableRatings reports the distinct ratings sorted`() = runBlocking {
        val viewer = createViewerUser(username = "list-avail-ratings")
        createTitle(name = "A", contentRating = "TV-MA")
        createTitle(name = "B", contentRating = "PG")
        createTitle(name = "C", contentRating = "PG")
        createTitle(name = "D", contentRating = null)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { })
            // Distinct + sorted; null skipped.
            assertEquals(listOf("PG", "TV-MA"), resp.availableRatingsList.toList())
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- sort ----------------------

    @Test
    fun `listTitles default sort is popularity descending`() = runBlocking {
        val viewer = createViewerUser(username = "list-sort-pop")
        createTitle(name = "Mid", popularity = 50.0)
        createTitle(name = "Top", popularity = 99.0)
        createTitle(name = "Low", popularity = 1.0)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { })
            assertEquals(listOf("Top", "Mid", "Low"),
                resp.titlesList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles sort=name uses sort_name lowercased`() = runBlocking {
        val viewer = createViewerUser(username = "list-sort-name")
        // The base helper sets sort_name = name.lowercase().
        createTitle(name = "Zebra")
        createTitle(name = "Alpha")
        createTitle(name = "Mango")

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { sort = "name" })
            assertEquals(listOf("Alpha", "Mango", "Zebra"),
                resp.titlesList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listTitles sort=year is descending by release_year with nulls treated as zero`() = runBlocking {
        val viewer = createViewerUser(username = "list-sort-year")
        createTitle(name = "2024", releaseYear = 2024)
        createTitle(name = "1999", releaseYear = 1999)
        createTitle(name = "Untagged", releaseYear = null)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { sort = "year" })
            assertEquals(listOf("2024", "1999", "Untagged"),
                resp.titlesList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- rating ceiling ----------------------

    @Test
    fun `listTitles enforces caller's rating ceiling, hiding above-ceiling titles`() = runBlocking {
        val limited = createViewerUser(username = "list-ceiling").apply {
            rating_ceiling = ContentRating.TV_PG.ordinalLevel; save()
        }
        createTitle(name = "PG", contentRating = "PG")
        createTitle(name = "TV-MA", contentRating = "TV-MA")

        val authed = authenticatedChannel(limited)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authed)
            val resp = stub.listTitles(listTitlesRequest { })
            assertEquals(1, resp.titlesCount,
                "TV-MA filtered out by TV-PG ceiling")
            assertEquals("PG", resp.titlesList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }
}
