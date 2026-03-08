package net.stewart.mediamanager.service

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchIndexServiceTest {

    @BeforeTest
    fun setup() {
        SearchIndexService.clear()
    }

    @Test
    fun `tokenizer lowercases and strips punctuation`() {
        val tokens = SearchIndexService.tokenize("The Dark Knight: Rises!")
        assertEquals(listOf("the", "dark", "knight", "rises"), tokens)
    }

    @Test
    fun `tokenizer collapses whitespace`() {
        val tokens = SearchIndexService.tokenize("  hello   world  ")
        assertEquals(listOf("hello", "world"), tokens)
    }

    @Test
    fun `single term search finds matching title`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Batman Begins")

        val result = SearchIndexService.search("dark")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `search matches in description field`() {
        SearchIndexService.indexTitleForTest(1, "Inception", description = "A thief who steals secrets through dreams")

        val result = SearchIndexService.search("dreams")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `case insensitive search on title name`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")

        assertEquals(setOf(1L), SearchIndexService.search("DARK"))
        assertEquals(setOf(1L), SearchIndexService.search("dark"))
        assertEquals(setOf(1L), SearchIndexService.search("Dark"))
    }

    @Test
    fun `case insensitive search on description`() {
        SearchIndexService.indexTitleForTest(1, "Inception", description = "A Thief Who Steals Secrets")

        assertEquals(setOf(1L), SearchIndexService.search("THIEF"))
        assertEquals(setOf(1L), SearchIndexService.search("thief"))
        assertEquals(setOf(1L), SearchIndexService.search("Thief"))
    }

    @Test
    fun `case insensitive search on product name`() {
        SearchIndexService.indexTitleForTest(
            1, "The Matrix",
            productNames = listOf("THE MATRIX BLU-RAY ULTIMATE EDITION")
        )

        assertEquals(setOf(1L), SearchIndexService.search("ULTIMATE"))
        assertEquals(setOf(1L), SearchIndexService.search("ultimate"))
        assertEquals(setOf(1L), SearchIndexService.search("Ultimate"))
    }

    @Test
    fun `case insensitive search on character name`() {
        SearchIndexService.indexTitleForTest(
            1, "The Dark Knight",
            characterNames = listOf("Bruce Wayne / Batman")
        )

        assertEquals(setOf(1L), SearchIndexService.search("WAYNE"))
        assertEquals(setOf(1L), SearchIndexService.search("wayne"))
        assertEquals(setOf(1L), SearchIndexService.search("Wayne"))
    }

    @Test
    fun `case insensitive tag filter`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.addTagForTest("Action", 1)

        assertEquals(setOf(1L), SearchIndexService.search("tag:ACTION"))
        assertEquals(setOf(1L), SearchIndexService.search("tag:action"))
        assertEquals(setOf(1L), SearchIndexService.search("tag:Action"))
    }

    @Test
    fun `multi-term AND search`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Dark Shadows")
        SearchIndexService.indexTitleForTest(3, "A Knight's Tale")

        val result = SearchIndexService.search("dark knight")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `phrase match requires contiguous tokens`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Knight of the Dark")

        val result = SearchIndexService.search("\"dark knight\"")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `phrase does not match across fields`() {
        // "hello" is in name, "world" is in description — phrase should not match across
        SearchIndexService.indexTitleForTest(1, "Hello", description = "World")

        val result = SearchIndexService.search("\"hello world\"")
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `negation excludes matching titles`() {
        SearchIndexService.indexTitleForTest(1, "Batman Begins")
        SearchIndexService.indexTitleForTest(2, "Batman Returns")
        SearchIndexService.indexTitleForTest(3, "Superman Returns")

        val result = SearchIndexService.search("batman -returns")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `negation only starts with all titles`() {
        SearchIndexService.indexTitleForTest(1, "Batman Begins")
        SearchIndexService.indexTitleForTest(2, "Batman Returns")
        SearchIndexService.indexTitleForTest(3, "Superman Returns")

        val result = SearchIndexService.search("-returns")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `tag filter finds titles with matching tag`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Batman Begins")
        SearchIndexService.addTagForTest("action", 1)

        val result = SearchIndexService.search("tag:action")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `tag filter also matches genre`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.addGenreForTest("action", 1)

        val result = SearchIndexService.search("tag:action")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `tag plus term combo`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Iron Man")
        SearchIndexService.addTagForTest("action", 1)
        SearchIndexService.addTagForTest("action", 2)

        val result = SearchIndexService.search("tag:action knight")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `empty query returns null`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")

        val result = SearchIndexService.search("")
        assertNull(result)
    }

    @Test
    fun `no match returns empty set`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")

        val result = SearchIndexService.search("spiderman")
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `whitespace query returns null`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")

        val result = SearchIndexService.search("   ")
        assertNull(result)
    }

    @Test
    fun `raw_upc_title is searchable`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight", rawUpcTitle = "BATMAN DARK KNIGHT BLU-RAY")

        val result = SearchIndexService.search("bluray")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `sort_name is searchable`() {
        SearchIndexService.indexTitleForTest(1, "The Matrix", sortName = "Matrix, The")

        val result = SearchIndexService.search("matrix")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `product_name from media item is searchable`() {
        SearchIndexService.indexTitleForTest(
            1, "The Dark Knight",
            productNames = listOf("BATMAN DARK KNIGHT TRILOGY BLU-RAY COMBO PACK")
        )

        val result = SearchIndexService.search("trilogy")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `character_name from cast is searchable`() {
        SearchIndexService.indexTitleForTest(
            1, "The Dark Knight",
            characterNames = listOf("Bruce Wayne / Batman", "The Joker", "Harvey Dent")
        )

        val result = SearchIndexService.search("joker")
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `character name search does not match across fields`() {
        SearchIndexService.indexTitleForTest(
            1, "The Dark Knight",
            characterNames = listOf("Bruce Wayne")
        )

        // "knight bruce" spans title name and character name — should not phrase-match
        val result = SearchIndexService.search("\"knight bruce\"")
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `character name term combined with title term`() {
        SearchIndexService.indexTitleForTest(
            1, "The Dark Knight",
            characterNames = listOf("Bruce Wayne / Batman", "The Joker")
        )
        SearchIndexService.indexTitleForTest(
            2, "Joker",
            characterNames = listOf("Arthur Fleck")
        )

        // Both have "joker", but only title 1 has "knight"
        val result = SearchIndexService.search("joker knight")
        assertEquals(setOf(1L), result)
    }

    // --- Personal hide ("Hide for me") filtering tests ---
    // SearchIndexService is intentionally user-agnostic: it indexes ALL titles and
    // search() returns all matches. Personal hide filtering is the caller's
    // responsibility (CatalogView and MainLayout both post-filter). These tests
    // verify the contract: the index returns hidden titles, and the standard
    // caller-side pattern correctly excludes them.

    @Test
    fun `search returns personally-hidden titles because index is user-agnostic`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Crystal")

        // Both titles match "dark" — the index has no concept of personal hide
        val result = SearchIndexService.search("dark")
        assertEquals(setOf(1L, 2L), result)
    }

    @Test
    fun `caller-side filtering removes personally-hidden titles from search results`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Crystal")
        SearchIndexService.indexTitleForTest(3, "Batman Begins")

        // Simulate user having personally hidden title 2
        val personallyHiddenIds = setOf(2L)

        // Search returns all matches
        val searchResults = SearchIndexService.search("dark")!!

        // Caller filters out hidden titles (same pattern as CatalogView and MainLayout)
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()
        assertEquals(setOf(1L), visible)
    }

    @Test
    fun `caller-side filtering with empty hidden set returns all search results`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Crystal")

        val personallyHiddenIds = emptySet<Long>()
        val searchResults = SearchIndexService.search("dark")!!
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()

        assertEquals(setOf(1L, 2L), visible)
    }

    @Test
    fun `caller-side filtering when all results are hidden returns empty set`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Crystal")

        val personallyHiddenIds = setOf(1L, 2L)
        val searchResults = SearchIndexService.search("dark")!!
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `caller-side filtering hides from phrase search`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Knight Rises")

        val personallyHiddenIds = setOf(2L)
        val searchResults = SearchIndexService.search("\"dark knight\"")!!
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()

        assertEquals(setOf(1L), visible)
    }

    @Test
    fun `caller-side filtering hides from negation search`() {
        SearchIndexService.indexTitleForTest(1, "Batman Begins")
        SearchIndexService.indexTitleForTest(2, "Batman Returns")
        SearchIndexService.indexTitleForTest(3, "Superman Returns")

        // User hid title 1; search for batman excluding "returns"
        val personallyHiddenIds = setOf(1L)
        val searchResults = SearchIndexService.search("batman -returns")!!
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()

        // Title 1 matched "batman -returns" but is personally hidden
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `caller-side filtering hides from tag search`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Iron Man")
        SearchIndexService.addTagForTest("action", 1)
        SearchIndexService.addTagForTest("action", 2)

        val personallyHiddenIds = setOf(1L)
        val searchResults = SearchIndexService.search("tag:action")!!
        val visible = searchResults.filter { it !in personallyHiddenIds }.toSet()

        assertEquals(setOf(2L), visible)
    }

    @Test
    fun `caller-side filtering combines with full title list like CatalogView`() {
        // Simulates the CatalogView pattern: load all titles, filter hidden,
        // then intersect with search results
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "The Dark Crystal")
        SearchIndexService.indexTitleForTest(3, "Batman Begins")

        val allTitleIds = setOf(1L, 2L, 3L)
        val personallyHiddenIds = setOf(2L)
        val globallyHiddenIds = setOf(3L) // title.hidden = true

        // Step 1: remove personally hidden (CatalogView line ~293)
        var visible = allTitleIds - personallyHiddenIds
        // Step 2: remove globally hidden (CatalogView line ~299)
        visible = visible - globallyHiddenIds
        // Step 3: intersect with search results (CatalogView line ~321)
        val searchResults = SearchIndexService.search("dark")!!
        visible = visible.filter { it in searchResults }.toSet()

        // Only title 1 survives: title 2 is personally hidden, title 3 is globally hidden
        assertEquals(setOf(1L), visible)
    }

    @Test
    fun `removing a title removes it from search results`() {
        SearchIndexService.indexTitleForTest(1, "The Dark Knight")
        SearchIndexService.indexTitleForTest(2, "Dark Shadows")

        assertEquals(setOf(1L, 2L), SearchIndexService.search("dark"))

        // Simulate removing title 1 by clearing and re-indexing only title 2
        SearchIndexService.clear()
        SearchIndexService.indexTitleForTest(2, "Dark Shadows")

        val result = SearchIndexService.search("knight")
        assertTrue(result!!.isEmpty())

        // Title 2 still findable
        assertEquals(setOf(2L), SearchIndexService.search("shadows"))
    }
}
