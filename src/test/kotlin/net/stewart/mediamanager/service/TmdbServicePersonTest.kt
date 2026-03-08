package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbServicePersonTest {

    private val service = TmdbService()

    // --- parsePersonDetailsResponse ---

    @Test
    fun `parses full person details response`() {
        val body = """
        {
            "id": 56322,
            "name": "Jenna Ortega",
            "biography": "Jenna Marie Ortega is an American actress.",
            "birthday": "2002-09-27",
            "deathday": null,
            "place_of_birth": "Coachella Valley, California, USA",
            "known_for_department": "Acting",
            "profile_path": "/q1NRzyZQlYkBuoIDSLoFZEBrFxj.jpg",
            "popularity": 85.432
        }
        """.trimIndent()

        val result = service.parsePersonDetailsResponse(200, body)
        assertTrue(result.found)
        assertEquals("Jenna Ortega", result.name)
        assertEquals("Jenna Marie Ortega is an American actress.", result.biography)
        assertEquals("2002-09-27", result.birthday)
        assertNull(result.deathday)
        assertEquals("Coachella Valley, California, USA", result.placeOfBirth)
        assertEquals("Acting", result.knownForDepartment)
        assertEquals("/q1NRzyZQlYkBuoIDSLoFZEBrFxj.jpg", result.profilePath)
        assertEquals(85.432, result.popularity)
    }

    @Test
    fun `parses person with missing optional fields`() {
        val body = """
        {
            "id": 99999,
            "name": "Mystery Actor"
        }
        """.trimIndent()

        val result = service.parsePersonDetailsResponse(200, body)
        assertTrue(result.found)
        assertEquals("Mystery Actor", result.name)
        assertNull(result.biography)
        assertNull(result.birthday)
        assertNull(result.deathday)
        assertNull(result.placeOfBirth)
        assertNull(result.knownForDepartment)
        assertNull(result.profilePath)
        assertNull(result.popularity)
    }

    @Test
    fun `parses deceased person with deathday`() {
        val body = """
        {
            "id": 3084,
            "name": "Marlon Brando",
            "biography": "Marlon Brando Jr. was an American actor.",
            "birthday": "1924-04-03",
            "deathday": "2004-07-01",
            "known_for_department": "Acting",
            "profile_path": "/fuTEPMsBtV1zE98ujPONbKiYDc2.jpg"
        }
        """.trimIndent()

        val result = service.parsePersonDetailsResponse(200, body)
        assertTrue(result.found)
        assertEquals("1924-04-03", result.birthday)
        assertEquals("2004-07-01", result.deathday)
    }

    @Test
    fun `404 returns not found`() {
        val result = service.parsePersonDetailsResponse(404, """{"status_message":"not found"}""")
        assertFalse(result.found)
        assertEquals("Person not found (404)", result.errorMessage)
    }

    @Test
    fun `non-200 non-404 returns error`() {
        val result = service.parsePersonDetailsResponse(500, "")
        assertFalse(result.found)
        assertEquals("HTTP 500", result.errorMessage)
    }

    @Test
    fun `malformed json for person details returns error`() {
        val result = service.parsePersonDetailsResponse(200, "not json")
        assertFalse(result.found)
        assertTrue(result.errorMessage?.contains("JSON parse error") == true)
    }

    // --- parsePersonCreditsResponse ---

    @Test
    fun `parses combined credits with movies and TV`() {
        val body = """
        {
            "id": 56322,
            "cast": [
                {
                    "id": 119051,
                    "title": "Wednesday",
                    "media_type": "tv",
                    "name": "Wednesday",
                    "character": "Wednesday Addams",
                    "first_air_date": "2022-11-23",
                    "poster_path": "/wed.jpg",
                    "popularity": 150.5
                },
                {
                    "id": 646683,
                    "title": "Scream VI",
                    "media_type": "movie",
                    "character": "Tara Carpenter",
                    "release_date": "2023-03-08",
                    "poster_path": "/scream6.jpg",
                    "popularity": 80.2
                }
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(2, results.size)

        // Sorted by popularity DESC
        assertEquals(119051, results[0].tmdbId)
        assertEquals("Wednesday", results[0].title)
        assertEquals("TV", results[0].mediaType)
        assertEquals("Wednesday Addams", results[0].characterName)
        assertEquals(2022, results[0].releaseYear)
        assertEquals("/wed.jpg", results[0].posterPath)
        assertEquals(150.5, results[0].popularity)

        assertEquals(646683, results[1].tmdbId)
        assertEquals("Scream VI", results[1].title)
        assertEquals("MOVIE", results[1].mediaType)
        assertEquals("Tara Carpenter", results[1].characterName)
        assertEquals(2023, results[1].releaseYear)
    }

    @Test
    fun `deduplicates by tmdb id`() {
        val body = """
        {
            "id": 56322,
            "cast": [
                {
                    "id": 100,
                    "title": "Movie A",
                    "media_type": "movie",
                    "character": "Role 1",
                    "release_date": "2020-01-01",
                    "popularity": 50.0
                },
                {
                    "id": 100,
                    "title": "Movie A",
                    "media_type": "movie",
                    "character": "Role 2",
                    "release_date": "2020-01-01",
                    "popularity": 50.0
                },
                {
                    "id": 200,
                    "title": "Movie B",
                    "media_type": "movie",
                    "character": "Role 3",
                    "release_date": "2021-05-15",
                    "popularity": 30.0
                }
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(2, results.size)
        assertEquals(100, results[0].tmdbId)
        assertEquals(200, results[1].tmdbId)
    }

    @Test
    fun `caps at 50 entries`() {
        val castEntries = (1..80).joinToString(",") { i ->
            """{"id": $i, "title": "Movie $i", "media_type": "movie", "character": "Role $i", "release_date": "2020-01-01", "popularity": ${80 - i}.0}"""
        }
        val body = """{"id": 1, "cast": [$castEntries]}"""

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(50, results.size)
    }

    @Test
    fun `sorts by popularity descending`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "title": "Low", "media_type": "movie", "release_date": "2020-01-01", "popularity": 5.0},
                {"id": 2, "title": "High", "media_type": "movie", "release_date": "2020-01-01", "popularity": 100.0},
                {"id": 3, "title": "Mid", "media_type": "movie", "release_date": "2020-01-01", "popularity": 50.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(3, results.size)
        assertEquals("High", results[0].title)
        assertEquals("Mid", results[1].title)
        assertEquals("Low", results[2].title)
    }

    @Test
    fun `empty cast array returns empty list`() {
        val body = """{"id": 1, "cast": []}"""
        val results = service.parsePersonCreditsResponse(200, body)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-200 status for credits returns empty list`() {
        val results = service.parsePersonCreditsResponse(429, "")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `skips entries with unknown media type`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "title": "Movie", "media_type": "movie", "release_date": "2020-01-01", "popularity": 50.0},
                {"id": 2, "title": "Person", "media_type": "person", "release_date": "2020-01-01", "popularity": 30.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Movie", results[0].title)
    }

    @Test
    fun `skips entries missing required title field`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "media_type": "movie", "release_date": "2020-01-01", "popularity": 50.0},
                {"id": 2, "title": "Has Title", "media_type": "movie", "release_date": "2021-01-01", "popularity": 30.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Has Title", results[0].title)
    }

    @Test
    fun `handles missing optional fields in credits`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {
                    "id": 1,
                    "title": "Minimal Movie",
                    "media_type": "movie",
                    "popularity": 10.0
                }
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Minimal Movie", results[0].title)
        assertNull(results[0].characterName)
        assertNull(results[0].releaseYear)
        assertNull(results[0].posterPath)
    }

    @Test
    fun `malformed json for credits returns empty list`() {
        val results = service.parsePersonCreditsResponse(200, "not json")
        assertTrue(results.isEmpty())
    }

    // --- Low-relevance credit filtering ---

    @Test
    fun `filters talk show credits by genre`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "title": "Good Movie", "media_type": "movie", "release_date": "2020-01-01", "popularity": 50.0},
                {"id": 2, "name": "The Late Show with Stephen Colbert", "media_type": "tv", "first_air_date": "2015-09-08", "genre_ids": [10767], "character": "Self", "episode_count": 2, "popularity": 200.0},
                {"id": 3, "name": "Real Drama Show", "media_type": "tv", "first_air_date": "2019-01-01", "genre_ids": [18], "character": "Detective", "episode_count": 12, "popularity": 80.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(2, results.size)
        assertEquals("Real Drama Show", results[0].title)
        assertEquals("Good Movie", results[1].title)
    }

    @Test
    fun `filters news show credits by genre`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "CNN Tonight", "media_type": "tv", "first_air_date": "2014-01-01", "genre_ids": [10763], "character": "Self", "popularity": 30.0},
                {"id": 2, "title": "Action Movie", "media_type": "movie", "release_date": "2022-06-01", "popularity": 60.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Action Movie", results[0].title)
    }

    @Test
    fun `filters reality show credits by genre`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Celebrity Big Brother", "media_type": "tv", "first_air_date": "2018-02-07", "genre_ids": [10764], "character": "Self", "popularity": 25.0},
                {"id": 2, "name": "Legit Show", "media_type": "tv", "first_air_date": "2020-01-01", "genre_ids": [18, 80], "character": "Lead Role", "popularity": 70.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Legit Show", results[0].title)
    }

    @Test
    fun `filters soap opera credits by genre`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Days of Our Lives", "media_type": "tv", "first_air_date": "1965-11-08", "genre_ids": [10766], "character": "Self", "popularity": 40.0},
                {"id": 2, "title": "Thriller", "media_type": "movie", "release_date": "2023-01-01", "popularity": 55.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Thriller", results[0].title)
    }

    @Test
    fun `filters Self character with low episode count`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Documentary Series", "media_type": "tv", "first_air_date": "2019-01-01", "genre_ids": [99], "character": "Self", "episode_count": 1, "popularity": 30.0},
                {"id": 2, "name": "Drama Series", "media_type": "tv", "first_air_date": "2020-01-01", "genre_ids": [18], "character": "Detective Jones", "episode_count": 24, "popularity": 80.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Drama Series", results[0].title)
    }

    @Test
    fun `keeps Self character with high episode count`() {
        // A documentary series host with many episodes is a real credit
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Cosmos", "media_type": "tv", "first_air_date": "2014-03-09", "genre_ids": [99], "character": "Self", "episode_count": 13, "popularity": 60.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Cosmos", results[0].title)
    }

    @Test
    fun `filters Themselves and Himself variants`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Award Show", "media_type": "tv", "first_air_date": "2020-01-01", "genre_ids": [99], "character": "Themselves", "episode_count": 1, "popularity": 30.0},
                {"id": 2, "name": "Interview Special", "media_type": "tv", "first_air_date": "2021-01-01", "genre_ids": [99], "character": "Himself", "episode_count": 2, "popularity": 25.0},
                {"id": 3, "name": "Guest Spot", "media_type": "tv", "first_air_date": "2022-01-01", "genre_ids": [18], "character": "Herself", "episode_count": 1, "popularity": 20.0},
                {"id": 4, "title": "Real Movie", "media_type": "movie", "release_date": "2023-01-01", "popularity": 50.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Real Movie", results[0].title)
    }

    @Test
    fun `filters Self with voice suffix`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Voice Cameo Show", "media_type": "tv", "first_air_date": "2020-01-01", "genre_ids": [16], "character": "Self (voice)", "episode_count": 1, "popularity": 30.0},
                {"id": 2, "title": "Movie", "media_type": "movie", "release_date": "2023-01-01", "popularity": 50.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Movie", results[0].title)
    }

    @Test
    fun `noise genre filtering takes priority over named character`() {
        // Even a named character on a talk show is noise
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "The Tonight Show", "media_type": "tv", "first_air_date": "2014-02-17", "genre_ids": [10767, 35], "character": "Musical Guest", "episode_count": 3, "popularity": 100.0},
                {"id": 2, "title": "Drama", "media_type": "movie", "release_date": "2020-01-01", "popularity": 40.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Drama", results[0].title)
    }

    @Test
    fun `movies are never filtered regardless of character name`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "title": "Documentary Film", "media_type": "movie", "release_date": "2020-01-01", "character": "Self", "popularity": 30.0},
                {"id": 2, "title": "Concert Film", "media_type": "movie", "release_date": "2021-01-01", "character": "Himself", "popularity": 25.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(2, results.size)
    }

    @Test
    fun `TV credits without genre_ids are not filtered by genre`() {
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Unknown Show", "media_type": "tv", "first_air_date": "2020-01-01", "character": "Detective", "popularity": 30.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertEquals("Unknown Show", results[0].title)
    }

    @Test
    fun `Self without episode_count is not filtered`() {
        // No episode_count means we can't tell if it's a guest spot — keep it
        val body = """
        {
            "id": 1,
            "cast": [
                {"id": 1, "name": "Mystery Show", "media_type": "tv", "first_air_date": "2020-01-01", "genre_ids": [99], "character": "Self", "popularity": 30.0}
            ]
        }
        """.trimIndent()

        val results = service.parsePersonCreditsResponse(200, body)
        assertEquals(1, results.size)
    }

    // --- isLowRelevanceCredit unit tests ---

    @Test
    fun `isLowRelevanceCredit returns true for talk show genre`() {
        assertTrue(TmdbService.isLowRelevanceCredit(setOf(10767), "Self", 1))
    }

    @Test
    fun `isLowRelevanceCredit returns true for news genre`() {
        assertTrue(TmdbService.isLowRelevanceCredit(setOf(10763), null, null))
    }

    @Test
    fun `isLowRelevanceCredit returns true for reality genre`() {
        assertTrue(TmdbService.isLowRelevanceCredit(setOf(10764), "Contestant", 5))
    }

    @Test
    fun `isLowRelevanceCredit returns true for soap genre`() {
        assertTrue(TmdbService.isLowRelevanceCredit(setOf(10766), null, null))
    }

    @Test
    fun `isLowRelevanceCredit returns true for noise genre mixed with non-noise`() {
        assertTrue(TmdbService.isLowRelevanceCredit(setOf(35, 10767), "Guest", 1))
    }

    @Test
    fun `isLowRelevanceCredit returns true for Self with 1 episode`() {
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "Self", 1))
    }

    @Test
    fun `isLowRelevanceCredit returns true for Themselves with 3 episodes`() {
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "Themselves", 3))
    }

    @Test
    fun `isLowRelevanceCredit returns false for Self with 4 episodes`() {
        assertFalse(TmdbService.isLowRelevanceCredit(emptySet(), "Self", 4))
    }

    @Test
    fun `isLowRelevanceCredit returns false for named character`() {
        assertFalse(TmdbService.isLowRelevanceCredit(emptySet(), "Detective Jones", 1))
    }

    @Test
    fun `isLowRelevanceCredit returns false for no genre and no self`() {
        assertFalse(TmdbService.isLowRelevanceCredit(emptySet(), "Lead Role", 24))
    }

    @Test
    fun `isLowRelevanceCredit returns false when character is null and no noise genre`() {
        assertFalse(TmdbService.isLowRelevanceCredit(emptySet(), null, 1))
    }

    @Test
    fun `isLowRelevanceCredit handles Host character`() {
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "Host", 2))
    }

    @Test
    fun `isLowRelevanceCredit handles Guest character`() {
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "Guest", 1))
    }

    @Test
    fun `isLowRelevanceCredit case insensitive`() {
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "SELF", 1))
        assertTrue(TmdbService.isLowRelevanceCredit(emptySet(), "himself", 2))
    }
}
