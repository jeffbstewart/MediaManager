package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ContentRatingTest {

    @Test
    fun `fromTmdbCertification parses MPAA ratings`() {
        assertEquals(ContentRating.G, ContentRating.fromTmdbCertification("G"))
        assertEquals(ContentRating.PG, ContentRating.fromTmdbCertification("PG"))
        assertEquals(ContentRating.PG_13, ContentRating.fromTmdbCertification("PG-13"))
        assertEquals(ContentRating.R, ContentRating.fromTmdbCertification("R"))
        assertEquals(ContentRating.NC_17, ContentRating.fromTmdbCertification("NC-17"))
    }

    @Test
    fun `fromTmdbCertification parses TV ratings`() {
        assertEquals(ContentRating.TV_Y, ContentRating.fromTmdbCertification("TV-Y"))
        assertEquals(ContentRating.TV_Y7, ContentRating.fromTmdbCertification("TV-Y7"))
        assertEquals(ContentRating.TV_G, ContentRating.fromTmdbCertification("TV-G"))
        assertEquals(ContentRating.TV_PG, ContentRating.fromTmdbCertification("TV-PG"))
        assertEquals(ContentRating.TV_14, ContentRating.fromTmdbCertification("TV-14"))
        assertEquals(ContentRating.TV_MA, ContentRating.fromTmdbCertification("TV-MA"))
    }

    @Test
    fun `fromTmdbCertification is case insensitive`() {
        assertEquals(ContentRating.PG_13, ContentRating.fromTmdbCertification("pg-13"))
        assertEquals(ContentRating.TV_MA, ContentRating.fromTmdbCertification("tv-ma"))
        assertEquals(ContentRating.R, ContentRating.fromTmdbCertification("r"))
    }

    @Test
    fun `fromTmdbCertification trims whitespace`() {
        assertEquals(ContentRating.R, ContentRating.fromTmdbCertification("  R  "))
        assertEquals(ContentRating.PG_13, ContentRating.fromTmdbCertification(" PG-13 "))
    }

    @Test
    fun `fromTmdbCertification returns null for blank or null`() {
        assertNull(ContentRating.fromTmdbCertification(null))
        assertNull(ContentRating.fromTmdbCertification(""))
        assertNull(ContentRating.fromTmdbCertification("   "))
    }

    @Test
    fun `fromTmdbCertification returns null for unknown ratings`() {
        assertNull(ContentRating.fromTmdbCertification("X"))
        assertNull(ContentRating.fromTmdbCertification("NR"))
        assertNull(ContentRating.fromTmdbCertification("Unrated"))
    }

    @Test
    fun `ordinal levels are correct for cross-system comparison`() {
        assertEquals(0, ContentRating.TV_Y.ordinalLevel)
        assertEquals(1, ContentRating.TV_Y7.ordinalLevel)
        assertEquals(2, ContentRating.G.ordinalLevel)
        assertEquals(2, ContentRating.TV_G.ordinalLevel)
        assertEquals(3, ContentRating.PG.ordinalLevel)
        assertEquals(3, ContentRating.TV_PG.ordinalLevel)
        assertEquals(4, ContentRating.PG_13.ordinalLevel)
        assertEquals(4, ContentRating.TV_14.ordinalLevel)
        assertEquals(5, ContentRating.R.ordinalLevel)
        assertEquals(5, ContentRating.TV_MA.ordinalLevel)
        assertEquals(6, ContentRating.NC_17.ordinalLevel)
    }

    @Test
    fun `G and TV-G share the same ordinal level`() {
        assertEquals(ContentRating.G.ordinalLevel, ContentRating.TV_G.ordinalLevel)
    }

    @Test
    fun `PG and TV-PG share the same ordinal level`() {
        assertEquals(ContentRating.PG.ordinalLevel, ContentRating.TV_PG.ordinalLevel)
    }

    @Test
    fun `PG-13 and TV-14 share the same ordinal level`() {
        assertEquals(ContentRating.PG_13.ordinalLevel, ContentRating.TV_14.ordinalLevel)
    }

    @Test
    fun `R and TV-MA share the same ordinal level`() {
        assertEquals(ContentRating.R.ordinalLevel, ContentRating.TV_MA.ordinalLevel)
    }

    @Test
    fun `ceilingChoices returns 7 ordered options`() {
        val choices = ContentRating.ceilingChoices()
        assertEquals(7, choices.size)
        assertEquals(0, choices.first().first)
        assertEquals(6, choices.last().first)
        // Verify ascending order
        for (i in 1 until choices.size) {
            assert(choices[i].first > choices[i - 1].first) {
                "Choices should be in ascending ordinal order"
            }
        }
    }

    @Test
    fun `ceilingLabel returns correct labels`() {
        assertEquals("TV-Y", ContentRating.ceilingLabel(0))
        assertEquals("G / TV-G", ContentRating.ceilingLabel(2))
        assertEquals("PG-13 / TV-14", ContentRating.ceilingLabel(4))
        assertEquals("R / TV-MA", ContentRating.ceilingLabel(5))
        assertEquals("NC-17", ContentRating.ceilingLabel(6))
    }

    @Test
    fun `ceilingLabel returns Unknown for invalid level`() {
        assertEquals("Unknown", ContentRating.ceilingLabel(99))
        assertEquals("Unknown", ContentRating.ceilingLabel(-1))
    }
}
