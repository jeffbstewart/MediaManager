package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzyMatchServiceTest {

    private fun title(id: Long, name: String, sortName: String? = null) =
        Title(id = id, name = name, sort_name = sortName)

    // --- Levenshtein distance ---

    @Test
    fun `identical strings have distance 0`() {
        assertEquals(0, FuzzyMatchService.levenshtein("hello", "hello"))
    }

    @Test
    fun `empty strings have distance 0`() {
        assertEquals(0, FuzzyMatchService.levenshtein("", ""))
    }

    @Test
    fun `one empty string gives length of other`() {
        assertEquals(5, FuzzyMatchService.levenshtein("hello", ""))
        assertEquals(3, FuzzyMatchService.levenshtein("", "abc"))
    }

    @Test
    fun `single character substitution`() {
        assertEquals(1, FuzzyMatchService.levenshtein("cat", "bat"))
    }

    @Test
    fun `single character insertion`() {
        assertEquals(1, FuzzyMatchService.levenshtein("cat", "cats"))
    }

    @Test
    fun `single character deletion`() {
        assertEquals(1, FuzzyMatchService.levenshtein("cats", "cat"))
    }

    @Test
    fun `multiple edits`() {
        assertEquals(3, FuzzyMatchService.levenshtein("kitten", "sitting"))
    }

    // --- Similarity ---

    @Test
    fun `identical strings have similarity 1`() {
        assertEquals(1.0, FuzzyMatchService.similarity("Inception", "Inception"))
    }

    @Test
    fun `similarity is case-insensitive and normalized`() {
        // Both normalize to "inception"
        assertEquals(1.0, FuzzyMatchService.similarity("Inception", "inception"))
    }

    @Test
    fun `similarity accounts for article stripping`() {
        // "The Dark Knight" normalizes to "dark knight", "Dark Knight" also normalizes to "dark knight"
        assertEquals(1.0, FuzzyMatchService.similarity("The Dark Knight", "Dark Knight"))
    }

    @Test
    fun `similarity with typo is high but less than 1`() {
        // "Inceptoin" vs "Inception" — one transposition in normalized form
        val score = FuzzyMatchService.similarity("Inceptoin", "Inception")
        assertTrue(score > 0.7, "Expected > 0.7 but was $score")
        assertTrue(score < 1.0, "Expected < 1.0 but was $score")
    }

    @Test
    fun `completely different strings have low similarity`() {
        val score = FuzzyMatchService.similarity("Inception", "Frozen")
        assertTrue(score < 0.5, "Expected < 0.5 but was $score")
    }

    @Test
    fun `both empty strings have similarity 1`() {
        assertEquals(1.0, FuzzyMatchService.similarity("", ""))
    }

    // --- findSuggestions ---

    private val catalog = listOf(
        title(1, "Inception"),
        title(2, "The Dark Knight", sortName = "Dark Knight"),
        title(3, "Interstellar"),
        title(4, "Die Hard"),
        title(5, "The Matrix", sortName = "Matrix"),
        title(6, "Frozen"),
        title(7, "Star Trek: Discovery")
    )

    @Test
    fun `finds suggestions for a near-miss typo`() {
        val results = FuzzyMatchService.findSuggestions("Inceptoin", catalog)
        assertTrue(results.isNotEmpty(), "Expected at least one suggestion")
        assertEquals(1L, results.first().title.id, "Expected Inception as top suggestion")
    }

    @Test
    fun `finds suggestions for missing colon`() {
        val results = FuzzyMatchService.findSuggestions("Star Trek Discovery", catalog)
        assertTrue(results.isNotEmpty(), "Expected at least one suggestion")
        assertEquals(7L, results.first().title.id, "Expected Star Trek: Discovery as top suggestion")
        assertEquals(1.0, results.first().score, "Normalized forms should be identical")
    }

    @Test
    fun `matches against sort_name for higher score`() {
        // "Matrix" matches sort_name "Matrix" better than title "The Matrix"
        val results = FuzzyMatchService.findSuggestions("Matrix", catalog)
        assertTrue(results.isNotEmpty())
        assertEquals(5L, results.first().title.id)
        assertEquals(1.0, results.first().score)
    }

    @Test
    fun `respects threshold`() {
        // With a very high threshold, only near-perfect matches qualify
        val results = FuzzyMatchService.findSuggestions("Inception", catalog, threshold = 0.99)
        assertEquals(1, results.size, "Only exact match should pass 0.99 threshold")
    }

    @Test
    fun `respects maxResults`() {
        // Low threshold to get many matches
        val results = FuzzyMatchService.findSuggestions("In", catalog, maxResults = 2, threshold = 0.01)
        assertTrue(results.size <= 2, "Expected at most 2 results but got ${results.size}")
    }

    @Test
    fun `returns empty for no match above threshold`() {
        val results = FuzzyMatchService.findSuggestions("ZZZZZZZZZZZZZ", catalog, threshold = 0.90)
        assertTrue(results.isEmpty(), "Expected no suggestions for garbage input")
    }

    @Test
    fun `results are sorted by score descending`() {
        val results = FuzzyMatchService.findSuggestions("Interstellar", catalog, threshold = 0.3)
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].score >= results[i + 1].score,
                "Results should be sorted by score descending"
            )
        }
    }

    @Test
    fun `abbreviated word still ranks well`() {
        // "Interstllar" (missing 'e') vs "Interstellar"
        val results = FuzzyMatchService.findSuggestions("Interstllar", catalog)
        assertTrue(results.isNotEmpty(), "Expected at least one suggestion")
        assertEquals(3L, results.first().title.id, "Expected Interstellar as top suggestion")
    }

    @Test
    fun `hidden titles are excluded from suggestions`() {
        val titles = listOf(
            title(1, "Inception"),
            Title(id = 2, name = "Interstellar", hidden = true)
        )
        val results = FuzzyMatchService.findSuggestions("Interstellar", titles)
        assertTrue(results.none { it.title.id == 2L }, "Hidden title should not appear in suggestions")
    }
}
