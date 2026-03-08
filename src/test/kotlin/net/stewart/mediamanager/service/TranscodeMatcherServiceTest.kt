package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.MatchMethod
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscodeMatcherServiceTest {

    private fun title(id: Long, name: String, year: Int? = null, sortName: String? = null,
                      mediaType: String = MediaType.MOVIE.name) =
        Title(id = id, name = name, release_year = year, sort_name = sortName, media_type = mediaType)

    private val catalog = listOf(
        title(1, "Inception", 2010),
        title(2, "The Dark Knight", 2008, sortName = "Dark Knight"),
        title(3, "Die Hard", 1988),
        title(4, "Die Hard", 2013, sortName = "Die Hard"),  // duplicate name, different year
        title(5, "Schindler's List", 1993),
        title(6, "Breaking Bad", 2008, mediaType = MediaType.TV.name),
        title(7, "The Karate Kid", 1984, sortName = "Karate Kid"),
        title(8, "Star Trek: Discovery", 2017)
    )

    // --- Movie matching ---

    @Test
    fun `exact match by name`() {
        val parsed = ParsedFile(title = "Inception")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(1L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `exact match is case-insensitive`() {
        val parsed = ParsedFile(title = "inception")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(1L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `year disambiguates duplicate exact matches`() {
        val parsed = ParsedFile(title = "Die Hard", year = 2013)
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(4L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `normalized match strips leading article`() {
        // File parsed as "Dark Knight" (leading "The" stripped), catalog has "The Dark Knight"
        val parsed = ParsedFile(title = "Dark Knight")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(2L, result.titleId)
        assertEquals(MatchMethod.AUTO_NORMALIZED, result.method)
    }

    @Test
    fun `normalized match strips punctuation`() {
        // "Schindlers List" (no apostrophe) matches "Schindler's List"
        val parsed = ParsedFile(title = "Schindlers List")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(5L, result.titleId)
        assertEquals(MatchMethod.AUTO_NORMALIZED, result.method)
    }

    @Test
    fun `normalized match on sort_name`() {
        // File "Karate Kid" matches title with sort_name "Karate Kid"
        val parsed = ParsedFile(title = "Karate Kid")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(7L, result.titleId)
        assertEquals(MatchMethod.AUTO_NORMALIZED, result.method)
    }

    @Test
    fun `normalized match strips colon`() {
        // "Star Trek Discovery" (no colon) matches "Star Trek: Discovery"
        val parsed = ParsedFile(title = "Star Trek Discovery")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertEquals(8L, result.titleId)
        assertEquals(MatchMethod.AUTO_NORMALIZED, result.method)
    }

    @Test
    fun `no match returns empty result`() {
        val parsed = ParsedFile(title = "Nonexistent Movie")
        val result = TranscodeMatcherService.matchMovie(parsed, catalog)
        assertNull(result.titleId)
        assertNull(result.method)
    }

    // --- TV matching ---

    @Test
    fun `TV show match by name`() {
        val result = TranscodeMatcherService.matchTvShow("Breaking Bad", catalog)
        assertEquals(6L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `TV show no match`() {
        val result = TranscodeMatcherService.matchTvShow("Unknown Show", catalog)
        assertNull(result.titleId)
    }

    // --- Media type disambiguation ---

    @Test
    fun `TV match prefers TV title over same-name movie`() {
        val mixedCatalog = listOf(
            title(10, "Buffy the Vampire Slayer", 1992, mediaType = MediaType.MOVIE.name),
            title(11, "Buffy the Vampire Slayer", 1997, mediaType = MediaType.TV.name)
        )
        val result = TranscodeMatcherService.matchTvShow("Buffy the Vampire Slayer", mixedCatalog)
        assertEquals(11L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `movie match prefers movie title over same-name TV show`() {
        val mixedCatalog = listOf(
            title(10, "Buffy the Vampire Slayer", 1992, mediaType = MediaType.MOVIE.name),
            title(11, "Buffy the Vampire Slayer", 1997, mediaType = MediaType.TV.name)
        )
        val parsed = ParsedFile(title = "Buffy the Vampire Slayer")
        val result = TranscodeMatcherService.matchMovie(parsed, mixedCatalog)
        assertEquals(10L, result.titleId)
        assertEquals(MatchMethod.AUTO_EXACT, result.method)
    }

    @Test
    fun `movie match with year still works when media types differ`() {
        val mixedCatalog = listOf(
            title(10, "Fargo", 1996, mediaType = MediaType.MOVIE.name),
            title(11, "Fargo", 2014, mediaType = MediaType.TV.name)
        )
        // Movie file with year 1996 — prefers MOVIE type, and year confirms
        val parsed = ParsedFile(title = "Fargo", year = 1996)
        val result = TranscodeMatcherService.matchMovie(parsed, mixedCatalog)
        assertEquals(10L, result.titleId)
    }

    @Test
    fun `TV match falls back to movie title if no TV title exists`() {
        // Only a movie exists — TV match should still return it rather than nothing
        val movieOnly = listOf(
            title(10, "Buffy the Vampire Slayer", 1992, mediaType = MediaType.MOVIE.name)
        )
        val result = TranscodeMatcherService.matchTvShow("Buffy the Vampire Slayer", movieOnly)
        assertEquals(10L, result.titleId)
    }

    @Test
    fun `media type disambiguation works with normalized matching`() {
        val mixedCatalog = listOf(
            title(10, "The Tick", 1994, mediaType = MediaType.MOVIE.name),
            title(11, "The Tick", 2017, mediaType = MediaType.TV.name)
        )
        // Normalized: "Tick" matches both "The Tick" entries
        val result = TranscodeMatcherService.matchTvShow("Tick", mixedCatalog)
        assertEquals(11L, result.titleId)
        assertEquals(MatchMethod.AUTO_NORMALIZED, result.method)
    }

    // --- Normalization ---

    @Test
    fun `normalize strips articles punctuation and collapses spaces`() {
        assertEquals("dark knight", TranscodeMatcherService.normalize("The Dark Knight"))
        assertEquals("schindlers list", TranscodeMatcherService.normalize("Schindler's List"))
        assertEquals("star trek discovery", TranscodeMatcherService.normalize("Star Trek: Discovery"))
    }
}
