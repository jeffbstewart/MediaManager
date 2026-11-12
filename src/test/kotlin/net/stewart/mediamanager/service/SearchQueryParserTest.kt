package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchQueryParserTest {

    @Test
    fun `empty input returns empty query`() {
        val result = SearchQueryParser.parse("")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `blank input returns empty query`() {
        val result = SearchQueryParser.parse("   ")
        assertTrue(result.isEmpty)
    }

    @Test
    fun `single word becomes required term`() {
        val result = SearchQueryParser.parse("batman")
        assertEquals(listOf("batman"), result.requiredTerms)
        assertTrue(result.phrases.isEmpty())
        assertTrue(result.excludedTerms.isEmpty())
        assertTrue(result.tagFilters.isEmpty())
    }

    @Test
    fun `quoted phrase is extracted`() {
        val result = SearchQueryParser.parse("\"dark knight\"")
        assertEquals(listOf("dark knight"), result.phrases)
        assertTrue(result.requiredTerms.isEmpty())
    }

    @Test
    fun `negation extracts excluded term`() {
        val result = SearchQueryParser.parse("-sequel")
        assertEquals(listOf("sequel"), result.excludedTerms)
        assertTrue(result.requiredTerms.isEmpty())
    }

    @Test
    fun `tag operator extracts tag filter`() {
        val result = SearchQueryParser.parse("tag:action")
        assertEquals(listOf("action"), result.tagFilters)
        assertTrue(result.requiredTerms.isEmpty())
    }

    @Test
    fun `complex mixed query`() {
        val result = SearchQueryParser.parse("\"dark knight\" -sequel tag:action batman")
        assertEquals(listOf("dark knight"), result.phrases)
        assertEquals(listOf("batman"), result.requiredTerms)
        assertEquals(listOf("sequel"), result.excludedTerms)
        assertEquals(listOf("action"), result.tagFilters)
    }

    @Test
    fun `case folding lowercases everything`() {
        val result = SearchQueryParser.parse("\"Dark Knight\" -SEQUEL tag:Action BATMAN")
        assertEquals(listOf("dark knight"), result.phrases)
        assertEquals(listOf("batman"), result.requiredTerms)
        assertEquals(listOf("sequel"), result.excludedTerms)
        assertEquals(listOf("action"), result.tagFilters)
    }

    @Test
    fun `unclosed quote treats rest as phrase`() {
        val result = SearchQueryParser.parse("\"dark knight")
        assertEquals(listOf("dark knight"), result.phrases)
    }

    @Test
    fun `bare hyphen is a required term`() {
        val result = SearchQueryParser.parse("-")
        // A bare "-" has nothing after it, so it's just a required term
        assertEquals(listOf("-"), result.requiredTerms)
        assertTrue(result.excludedTerms.isEmpty())
    }

    @Test
    fun `empty tag value is ignored`() {
        val result = SearchQueryParser.parse("tag:")
        assertTrue(result.tagFilters.isEmpty())
        // "tag:" with nothing after it becomes a required term
        assertEquals(listOf("tag:"), result.requiredTerms)
    }

    @Test
    fun `multiple phrases`() {
        val result = SearchQueryParser.parse("\"dark knight\" \"rises again\"")
        assertEquals(listOf("dark knight", "rises again"), result.phrases)
    }

    @Test
    fun `multiple required terms`() {
        val result = SearchQueryParser.parse("batman dark knight")
        assertEquals(listOf("batman", "dark", "knight"), result.requiredTerms)
    }
}
