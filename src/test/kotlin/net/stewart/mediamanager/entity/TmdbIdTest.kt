package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TmdbIdTest {

    @Test
    fun `equality uses both id and type`() {
        val movie253 = TmdbId(253, MediaType.MOVIE)
        val tv253 = TmdbId(253, MediaType.TV)

        assertNotEquals(movie253, tv253, "Same integer, different type must not be equal")
        assertEquals(movie253, TmdbId(253, MediaType.MOVIE), "Same id + type must be equal")
    }

    @Test
    fun `set membership distinguishes namespaces`() {
        val movie = TmdbId(100, MediaType.MOVIE)
        val tv = TmdbId(100, MediaType.TV)
        val set = setOf(movie)

        assertTrue(movie in set)
        assertTrue(tv !in set, "Different type must not match in a Set")
    }

    @Test
    fun `of factory returns null when id is null`() {
        assertNull(TmdbId.of(null, "MOVIE"))
    }

    @Test
    fun `of factory returns null when mediaType is null`() {
        assertNull(TmdbId.of(123, null))
    }

    @Test
    fun `of factory returns null when both null`() {
        assertNull(TmdbId.of(null, null))
    }

    @Test
    fun `of factory returns null for invalid media type`() {
        assertNull(TmdbId.of(123, "UNKNOWN"))
    }

    @Test
    fun `of factory returns TmdbId for valid inputs`() {
        val result = TmdbId.of(550, "MOVIE")
        assertNotNull(result)
        assertEquals(550, result.id)
        assertEquals(MediaType.MOVIE, result.type)
    }

    @Test
    fun `of factory parses TV type`() {
        val result = TmdbId.of(1396, "TV")
        assertNotNull(result)
        assertEquals(1396, result.id)
        assertEquals(MediaType.TV, result.type)
    }

    @Test
    fun `typeString returns enum name`() {
        assertEquals("MOVIE", TmdbId(1, MediaType.MOVIE).typeString)
        assertEquals("TV", TmdbId(1, MediaType.TV).typeString)
    }

    @Test
    fun `toString includes type and id`() {
        assertEquals("MOVIE:550", TmdbId(550, MediaType.MOVIE).toString())
        assertEquals("TV:1396", TmdbId(1396, MediaType.TV).toString())
    }

    @Test
    fun `hashCode differs for different namespaces`() {
        val movie = TmdbId(100, MediaType.MOVIE)
        val tv = TmdbId(100, MediaType.TV)
        // Not guaranteed by contract but highly expected for a good hash
        assertNotEquals(movie.hashCode(), tv.hashCode())
    }
}
