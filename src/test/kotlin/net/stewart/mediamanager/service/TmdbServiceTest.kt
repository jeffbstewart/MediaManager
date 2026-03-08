package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.TmdbId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbServiceTest {

    private val service = TmdbService()

    // --- Movie search: success ---

    @Test
    fun `movie search parses successful response`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 1858,
                "title": "The Golden Compass",
                "release_date": "2007-12-05",
                "overview": "In a parallel universe, a young girl journeys to the far North.",
                "poster_path": "/kMCehKisBi8MFjkDGRUzlza7VTU.jpg"
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertFalse(result.apiError)
        assertEquals(1858, result.tmdbId)
        assertEquals("The Golden Compass", result.title)
        assertEquals(2007, result.releaseYear)
        assertEquals("In a parallel universe, a young girl journeys to the far North.", result.overview)
        assertEquals("/kMCehKisBi8MFjkDGRUzlza7VTU.jpg", result.posterPath)
        assertEquals("MOVIE", result.mediaType)
    }

    @Test
    fun `movie search picks first result from multiple`() {
        val body = """
        {
            "page": 1,
            "results": [
                {"id": 100, "title": "First Match", "release_date": "2020-01-01", "overview": "First", "poster_path": "/first.jpg"},
                {"id": 200, "title": "Second Match", "release_date": "2021-01-01", "overview": "Second", "poster_path": "/second.jpg"}
            ],
            "total_results": 2
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertEquals(100, result.tmdbId)
        assertEquals("First Match", result.title)
    }

    // --- TV search: success ---

    @Test
    fun `tv search parses name and first_air_date fields`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 1399,
                "name": "Game of Thrones",
                "first_air_date": "2011-04-17",
                "overview": "Seven noble families fight for control.",
                "poster_path": "/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg"
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "TV")
        assertTrue(result.found)
        assertEquals(1399, result.tmdbId)
        assertEquals("Game of Thrones", result.title)
        assertEquals(2011, result.releaseYear)
        assertEquals("TV", result.mediaType)
        assertEquals("/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg", result.posterPath)
    }

    // --- Get details: movie ---

    @Test
    fun `get details parses movie response`() {
        val body = """
        {
            "id": 550,
            "title": "Fight Club",
            "release_date": "1999-10-15",
            "overview": "An insomniac office worker forms an underground fight club.",
            "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg"
        }
        """.trimIndent()

        val result = service.parseDetailsResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertEquals(550, result.tmdbId)
        assertEquals("Fight Club", result.title)
        assertEquals(1999, result.releaseYear)
        assertEquals("MOVIE", result.mediaType)
    }

    // --- Get details: TV ---

    @Test
    fun `get details parses tv response`() {
        val body = """
        {
            "id": 1396,
            "name": "Breaking Bad",
            "first_air_date": "2008-01-20",
            "overview": "A chemistry teacher diagnosed with terminal lung cancer.",
            "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"
        }
        """.trimIndent()

        val result = service.parseDetailsResponse(200, body, "TV")
        assertTrue(result.found)
        assertEquals(1396, result.tmdbId)
        assertEquals("Breaking Bad", result.title)
        assertEquals(2008, result.releaseYear)
        assertEquals("TV", result.mediaType)
    }

    // --- Empty results ---

    @Test
    fun `empty results array returns not found`() {
        val body = """{"page": 1, "results": [], "total_results": 0}"""
        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    @Test
    fun `missing results field returns not found`() {
        val body = """{"page": 1, "total_results": 0}"""
        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    // --- Missing API key ---

    @Test
    fun `search movie without api key returns skip result`() {
        val saved = System.getProperty("TMDB_API_KEY")
        try {
            System.clearProperty("TMDB_API_KEY")
            val localService = TmdbService()
            val result = localService.searchMovie("Test")
            assertFalse(result.found)
            assertFalse(result.apiError)
            assertEquals("No TMDB API key configured", result.errorMessage)
        } finally {
            if (saved != null) System.setProperty("TMDB_API_KEY", saved)
        }
    }

    @Test
    fun `search tv without api key returns skip result`() {
        val saved = System.getProperty("TMDB_API_KEY")
        try {
            System.clearProperty("TMDB_API_KEY")
            val localService = TmdbService()
            val result = localService.searchTv("Test")
            assertFalse(result.found)
            assertFalse(result.apiError)
            assertEquals("No TMDB API key configured", result.errorMessage)
        } finally {
            if (saved != null) System.setProperty("TMDB_API_KEY", saved)
        }
    }

    @Test
    fun `get details without api key returns skip result`() {
        val saved = System.getProperty("TMDB_API_KEY")
        try {
            System.clearProperty("TMDB_API_KEY")
            val localService = TmdbService()
            val result = localService.getDetails(TmdbId(550, MediaType.MOVIE))
            assertFalse(result.found)
            assertFalse(result.apiError)
            assertEquals("No TMDB API key configured", result.errorMessage)
        } finally {
            if (saved != null) System.setProperty("TMDB_API_KEY", saved)
        }
    }

    // --- API errors ---

    @Test
    fun `401 returns api error for invalid key`() {
        val result = service.parseSearchResponse(401, """{"status_message":"Invalid API key"}""", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("Invalid API key (401)", result.errorMessage)
    }

    @Test
    fun `429 returns api error for rate limiting`() {
        val result = service.parseSearchResponse(429, "", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("Rate limited (429)", result.errorMessage)
    }

    @Test
    fun `500 returns api error`() {
        val result = service.parseSearchResponse(500, "", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("HTTP 500", result.errorMessage)
    }

    @Test
    fun `details 404 returns not found without api error`() {
        val result = service.parseDetailsResponse(404, "", "MOVIE")
        assertFalse(result.found)
        assertFalse(result.apiError)
        assertEquals("TMDB ID not found (404)", result.errorMessage)
    }

    @Test
    fun `details 401 returns api error`() {
        val result = service.parseDetailsResponse(401, "", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
    }

    @Test
    fun `details 429 returns api error`() {
        val result = service.parseDetailsResponse(429, "", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
    }

    @Test
    fun `details 500 returns api error`() {
        val result = service.parseDetailsResponse(500, "", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
    }

    // --- Multi-result search ---

    @Test
    fun `multi-result movie search returns multiple results`() {
        val body = """
        {
            "page": 1,
            "results": [
                {"id": 100, "title": "First Match", "release_date": "2020-01-01", "overview": "First movie", "poster_path": "/first.jpg"},
                {"id": 200, "title": "Second Match", "release_date": "2021-01-01", "overview": "Second movie", "poster_path": "/second.jpg"},
                {"id": 300, "title": "Third Match", "release_date": "2022-01-01", "overview": "Third movie", "poster_path": "/third.jpg"}
            ],
            "total_results": 3
        }
        """.trimIndent()

        val results = service.parseSearchResponseMultiple(200, body, "MOVIE", 5)
        assertEquals(3, results.size)
        assertEquals(100, results[0].tmdbId)
        assertEquals("First Match", results[0].title)
        assertEquals(200, results[1].tmdbId)
        assertEquals("Second Match", results[1].title)
        assertEquals(300, results[2].tmdbId)
        assertEquals("Third Match", results[2].title)
        assertTrue(results.all { it.mediaType == "MOVIE" })
    }

    @Test
    fun `multi-result search respects maxResults limit`() {
        val body = """
        {
            "page": 1,
            "results": [
                {"id": 100, "title": "First", "release_date": "2020-01-01"},
                {"id": 200, "title": "Second", "release_date": "2021-01-01"},
                {"id": 300, "title": "Third", "release_date": "2022-01-01"}
            ],
            "total_results": 3
        }
        """.trimIndent()

        val results = service.parseSearchResponseMultiple(200, body, "MOVIE", 2)
        assertEquals(2, results.size)
        assertEquals(100, results[0].tmdbId)
        assertEquals(200, results[1].tmdbId)
    }

    @Test
    fun `multi-result TV search uses name and first_air_date`() {
        val body = """
        {
            "page": 1,
            "results": [
                {"id": 1399, "name": "Game of Thrones", "first_air_date": "2011-04-17", "overview": "Fantasy epic"},
                {"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "overview": "Chemistry teacher"}
            ],
            "total_results": 2
        }
        """.trimIndent()

        val results = service.parseSearchResponseMultiple(200, body, "TV", 5)
        assertEquals(2, results.size)
        assertEquals("Game of Thrones", results[0].title)
        assertEquals(2011, results[0].releaseYear)
        assertEquals("TV", results[0].mediaType)
        assertEquals("Breaking Bad", results[1].title)
        assertEquals(2008, results[1].releaseYear)
    }

    @Test
    fun `multi-result search with empty results returns empty list`() {
        val body = """{"page": 1, "results": [], "total_results": 0}"""
        val results = service.parseSearchResponseMultiple(200, body, "MOVIE")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `multi-result search with non-200 status returns empty list`() {
        val results = service.parseSearchResponseMultiple(429, "", "MOVIE")
        assertTrue(results.isEmpty())
    }

    // --- Malformed JSON ---

    @Test
    fun `malformed json returns api error`() {
        val result = service.parseSearchResponse(200, "not json at all", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage.orEmpty().startsWith("JSON parse error"))
    }

    @Test
    fun `malformed json in details returns api error`() {
        val result = service.parseDetailsResponse(200, "{broken", "MOVIE")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertTrue(result.errorMessage.orEmpty().startsWith("JSON parse error"))
    }

    // --- Null/missing fields in valid response ---

    @Test
    fun `null optional fields in response still parses`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 999,
                "title": "Minimal Movie",
                "release_date": null,
                "overview": null,
                "poster_path": null
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertEquals(999, result.tmdbId)
        assertEquals("Minimal Movie", result.title)
        assertNull(result.releaseYear)
        assertNull(result.overview)
        assertNull(result.posterPath)
    }

    @Test
    fun `missing optional fields in response still parses`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 888
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertEquals(888, result.tmdbId)
        assertNull(result.title)
        assertNull(result.releaseYear)
        assertNull(result.posterPath)
    }

    @Test
    fun `short release_date does not parse year`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 777,
                "title": "Short Date",
                "release_date": "20"
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertNull(result.releaseYear)
    }

    @Test
    fun `empty release_date treated as null`() {
        val body = """
        {
            "page": 1,
            "results": [{
                "id": 666,
                "title": "No Date",
                "release_date": ""
            }],
            "total_results": 1
        }
        """.trimIndent()

        val result = service.parseSearchResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertNull(result.releaseYear)
    }

    // --- Content rating extraction: Movies ---

    @Test
    fun `extractMovieRating finds US certification from release_dates`() {
        val body = """
        {
            "id": 550,
            "title": "Fight Club",
            "release_date": "1999-10-15",
            "overview": "An insomniac office worker...",
            "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
            "release_dates": {
                "results": [
                    {
                        "iso_3166_1": "GB",
                        "release_dates": [{"certification": "18", "type": 3}]
                    },
                    {
                        "iso_3166_1": "US",
                        "release_dates": [
                            {"certification": "", "type": 1},
                            {"certification": "R", "type": 3}
                        ]
                    }
                ]
            }
        }
        """.trimIndent()

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        val rating = service.extractMovieRating(item)
        assertEquals("R", rating)
    }

    @Test
    fun `extractMovieRating returns null when no US entry`() {
        val body = """
        {
            "id": 550,
            "title": "Foreign Movie",
            "release_dates": {
                "results": [
                    {
                        "iso_3166_1": "FR",
                        "release_dates": [{"certification": "12", "type": 3}]
                    }
                ]
            }
        }
        """.trimIndent()

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertNull(service.extractMovieRating(item))
    }

    @Test
    fun `extractMovieRating returns null when no release_dates key`() {
        val body = """{"id": 550, "title": "No Dates"}"""
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertNull(service.extractMovieRating(item))
    }

    @Test
    fun `extractMovieRating skips blank certifications`() {
        val body = """
        {
            "id": 550,
            "release_dates": {
                "results": [{
                    "iso_3166_1": "US",
                    "release_dates": [
                        {"certification": "", "type": 1},
                        {"certification": "PG-13", "type": 3}
                    ]
                }]
            }
        }
        """.trimIndent()

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertEquals("PG-13", service.extractMovieRating(item))
    }

    // --- Content rating extraction: TV ---

    @Test
    fun `extractTvRating finds US rating from content_ratings`() {
        val body = """
        {
            "id": 1399,
            "name": "Breaking Bad",
            "content_ratings": {
                "results": [
                    {"iso_3166_1": "DE", "rating": "16"},
                    {"iso_3166_1": "US", "rating": "TV-MA"}
                ]
            }
        }
        """.trimIndent()

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertEquals("TV-MA", service.extractTvRating(item))
    }

    @Test
    fun `extractTvRating returns null when no US entry`() {
        val body = """
        {
            "id": 1399,
            "content_ratings": {
                "results": [
                    {"iso_3166_1": "GB", "rating": "15"}
                ]
            }
        }
        """.trimIndent()

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertNull(service.extractTvRating(item))
    }

    @Test
    fun `extractTvRating returns null when no content_ratings key`() {
        val body = """{"id": 1399, "name": "No Ratings"}"""
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val item = mapper.readTree(body)
        assertNull(service.extractTvRating(item))
    }

    // --- Details response with content rating ---

    @Test
    fun `parseDetailsResponse extracts movie content rating`() {
        val body = """
        {
            "id": 550,
            "title": "Fight Club",
            "release_date": "1999-10-15",
            "overview": "An insomniac...",
            "poster_path": "/fc.jpg",
            "popularity": 80.5,
            "release_dates": {
                "results": [{
                    "iso_3166_1": "US",
                    "release_dates": [{"certification": "R", "type": 3}]
                }]
            }
        }
        """.trimIndent()

        val result = service.parseDetailsResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertEquals("R", result.contentRating)
        assertEquals("Fight Club", result.title)
    }

    @Test
    fun `parseDetailsResponse extracts TV content rating`() {
        val body = """
        {
            "id": 1399,
            "name": "Breaking Bad",
            "first_air_date": "2008-01-20",
            "overview": "A chemistry teacher...",
            "poster_path": "/bb.jpg",
            "popularity": 100.0,
            "content_ratings": {
                "results": [{"iso_3166_1": "US", "rating": "TV-MA"}]
            }
        }
        """.trimIndent()

        val result = service.parseDetailsResponse(200, body, "TV")
        assertTrue(result.found)
        assertEquals("TV-MA", result.contentRating)
        assertEquals("Breaking Bad", result.title)
    }

    @Test
    fun `parseDetailsResponse returns null contentRating when not available`() {
        val body = """
        {
            "id": 550,
            "title": "No Rating",
            "release_date": "1999-01-01",
            "release_dates": {"results": []}
        }
        """.trimIndent()

        val result = service.parseDetailsResponse(200, body, "MOVIE")
        assertTrue(result.found)
        assertNull(result.contentRating)
    }
}
