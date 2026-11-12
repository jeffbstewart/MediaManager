package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingCeilingTest {

    @Test
    fun `allows returns true at or below the ceiling`() {
        val pg13 = RatingCeiling(4) // PG-13 / TV-14
        assertTrue(pg13.allows(ContentRating.G))     // 2
        assertTrue(pg13.allows(ContentRating.PG))    // 3
        assertTrue(pg13.allows(ContentRating.PG_13)) // 4
        assertTrue(pg13.allows(ContentRating.TV_14)) // 4
    }

    @Test
    fun `allows returns false above the ceiling`() {
        val pg13 = RatingCeiling(4)
        assertFalse(pg13.allows(ContentRating.R))     // 5
        assertFalse(pg13.allows(ContentRating.TV_MA)) // 5
        assertFalse(pg13.allows(ContentRating.NC_17)) // 6
    }

    @Test
    fun `allows zero ceiling sees only TV-Y`() {
        val tvy = RatingCeiling(0)
        assertTrue(tvy.allows(ContentRating.TV_Y))    // 0
        assertFalse(tvy.allows(ContentRating.TV_Y7))  // 1
        assertFalse(tvy.allows(ContentRating.G))      // 2
    }

    @Test
    fun `allows max ceiling sees everything`() {
        val nc17 = RatingCeiling(6)
        // Every defined ContentRating is at or below the NC-17 ceiling.
        ContentRating.entries.forEach { rating ->
            assertTrue(nc17.allows(rating), "ceiling=6 should allow $rating")
        }
    }

    @Test
    fun `label maps to ContentRating ceilingLabel`() {
        assertEquals("TV-Y", RatingCeiling(0).label)
        assertEquals("G / TV-G", RatingCeiling(2).label)
        assertEquals("PG-13 / TV-14", RatingCeiling(4).label)
        assertEquals("R / TV-MA", RatingCeiling(5).label)
        assertEquals("NC-17", RatingCeiling(6).label)
    }

    @Test
    fun `label returns Unknown for out-of-range value`() {
        assertEquals("Unknown", RatingCeiling(99).label)
    }

    @Test
    fun `fromDb returns null for null input`() {
        assertNull(RatingCeiling.fromDb(null))
    }

    @Test
    fun `fromDb wraps non-null int`() {
        val rc = RatingCeiling.fromDb(3)
        assertEquals(3, rc?.ordinalLevel)
    }
}
