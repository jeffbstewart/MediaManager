package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TitleTest {

    // ---------------------- posterUrl ----------------------

    @Test
    fun `posterUrl THUMBNAIL returns servlet URL`() {
        val title = Title(id = 42, poster_path = "/abc123.jpg")
        assertEquals("/posters/w185/42", title.posterUrl(PosterSize.THUMBNAIL))
    }

    @Test
    fun `posterUrl FULL returns servlet URL`() {
        val title = Title(id = 42, poster_path = "/abc123.jpg")
        assertEquals("/posters/w500/42", title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl returns null when poster_path is null`() {
        val title = Title(id = 42, poster_path = null)
        assertNull(title.posterUrl(PosterSize.THUMBNAIL))
        assertNull(title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl includes title id in path`() {
        val title = Title(id = 99, poster_path = "/kMCehKisBi8MFjkDGRUzlza7VTU.jpg")
        assertEquals("/posters/w185/99", title.posterUrl(PosterSize.THUMBNAIL))
        assertEquals("/posters/w500/99", title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl personal video with cache id returns local-images URL`() {
        // PERSONAL videos with a cached hero use /local-images/<uuid>
        // regardless of size — the local image is always full-size.
        val title = Title(
            id = 700,
            media_type = MediaType.PERSONAL.name,
            poster_cache_id = "abc-uuid",
            poster_path = null,
        )
        assertEquals("/local-images/abc-uuid", title.posterUrl(PosterSize.THUMBNAIL))
        assertEquals("/local-images/abc-uuid", title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl personal video without cache id falls through to TMDB poster`() {
        val title = Title(
            id = 701,
            media_type = MediaType.PERSONAL.name,
            poster_cache_id = null,
            poster_path = "/tmdb.jpg",
        )
        assertEquals("/posters/w185/701", title.posterUrl(PosterSize.THUMBNAIL))
    }

    @Test
    fun `posterUrl non-personal media ignores cache id`() {
        val title = Title(
            id = 100,
            media_type = MediaType.MOVIE.name,
            poster_cache_id = "ignored",
            poster_path = "/m.jpg",
        )
        assertEquals("/posters/w185/100", title.posterUrl(PosterSize.THUMBNAIL))
    }

    // ---------------------- backdropUrl ----------------------

    @Test
    fun `backdropUrl returns servlet URL when path set`() {
        val title = Title(id = 42, backdrop_path = "/bd.jpg")
        assertEquals("/backdrops/42", title.backdropUrl())
    }

    @Test
    fun `backdropUrl returns null when path missing`() {
        assertNull(Title(id = 42, backdrop_path = null).backdropUrl())
    }

    // ---------------------- contentRatingEnum ----------------------

    @Test
    fun `contentRatingEnum parses TMDB certification`() {
        assertEquals(ContentRating.PG_13, Title(content_rating = "PG-13").contentRatingEnum())
        assertEquals(ContentRating.TV_MA, Title(content_rating = "TV-MA").contentRatingEnum())
        assertEquals(ContentRating.R, Title(content_rating = "r").contentRatingEnum())
    }

    @Test
    fun `contentRatingEnum returns null for unrated`() {
        assertNull(Title(content_rating = null).contentRatingEnum())
        assertNull(Title(content_rating = "").contentRatingEnum())
        assertNull(Title(content_rating = "X").contentRatingEnum())
    }

    // ---------------------- tmdbKey ----------------------

    @Test
    fun `tmdbKey returns key when both fields present`() {
        val key = Title(tmdb_id = 603, media_type = MediaType.MOVIE.name).tmdbKey()
        assertNotNull(key)
        assertEquals(603, key.id)
        assertEquals(MediaType.MOVIE, key.type)
    }

    @Test
    fun `tmdbKey returns null when tmdb_id missing`() {
        assertNull(Title(tmdb_id = null, media_type = MediaType.MOVIE.name).tmdbKey())
    }

    @Test
    fun `tmdbKey returns null when media_type is unknown`() {
        // Drives the null-mediaType branch on TmdbId.of when the
        // string doesn't match any MediaType enum entry.
        assertNull(Title(tmdb_id = 100, media_type = "BOGUS").tmdbKey())
    }

    // ---------------------- defaults ----------------------

    @Test
    fun `default media_type is MOVIE`() {
        assertEquals(MediaType.MOVIE.name, Title().media_type)
    }

    @Test
    fun `default enrichment_status is PENDING`() {
        assertEquals(EnrichmentStatus.PENDING.name, Title().enrichment_status)
    }

    @Test
    fun `default transcode_priority is zero`() {
        assertEquals(0, Title().transcode_priority)
    }
}
