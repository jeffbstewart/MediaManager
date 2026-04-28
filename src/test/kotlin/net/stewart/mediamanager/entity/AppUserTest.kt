package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUserTest {

    // ---------------------- isAdmin ----------------------

    @Test
    fun `isAdmin true at level 2 and above`() {
        assertTrue(AppUser(access_level = 2).isAdmin())
        assertTrue(AppUser(access_level = 3).isAdmin())
        assertTrue(AppUser(access_level = 99).isAdmin())
    }

    @Test
    fun `isAdmin false below level 2`() {
        assertFalse(AppUser(access_level = 0).isAdmin())
        assertFalse(AppUser(access_level = 1).isAdmin())
    }

    @Test
    fun `default access_level is viewer`() {
        // Default 1 = viewer, not admin.
        assertFalse(AppUser().isAdmin())
    }

    // ---------------------- ratingCeilingValue ----------------------

    @Test
    fun `ratingCeilingValue null when rating_ceiling is null`() {
        assertNull(AppUser(rating_ceiling = null).ratingCeilingValue)
    }

    @Test
    fun `ratingCeilingValue wraps int when present`() {
        val rc = AppUser(rating_ceiling = 4).ratingCeilingValue
        assertEquals(4, rc?.ordinalLevel)
    }

    // ---------------------- canSeeRating ----------------------

    @Test
    fun `canSeeRating admin sees everything`() {
        val admin = AppUser(access_level = 2, rating_ceiling = 0)
        // Even with a very strict ceiling, admins bypass.
        assertTrue(admin.canSeeRating("R"))
        assertTrue(admin.canSeeRating("NC-17"))
        // Including unrated titles, which non-admins are blocked from
        // when ceiling-restricted.
        assertTrue(admin.canSeeRating(null))
        assertTrue(admin.canSeeRating("Unknown"))
    }

    @Test
    fun `canSeeRating unrestricted user sees everything`() {
        val user = AppUser(access_level = 1, rating_ceiling = null)
        assertTrue(user.canSeeRating("R"))
        assertTrue(user.canSeeRating("PG"))
        assertTrue(user.canSeeRating(null))
    }

    @Test
    fun `canSeeRating ceiling-restricted user blocked from titles above ceiling`() {
        // PG-13 ceiling (ordinal 4) — sees PG (3) and below, not R (5).
        val user = AppUser(access_level = 1, rating_ceiling = 4)
        assertTrue(user.canSeeRating("G"))
        assertTrue(user.canSeeRating("PG"))
        assertTrue(user.canSeeRating("PG-13"))
        assertFalse(user.canSeeRating("R"))
        assertFalse(user.canSeeRating("NC-17"))
    }

    @Test
    fun `canSeeRating ceiling-restricted user blocked from unrated titles`() {
        val user = AppUser(access_level = 1, rating_ceiling = 5)
        // null rating from a ceiling-limited user is blocked — we don't
        // know what the unrated title contains.
        assertFalse(user.canSeeRating(null))
        assertFalse(user.canSeeRating(""))
        assertFalse(user.canSeeRating("Unknown"))
    }

    @Test
    fun `canSeeRating mixes MPAA and TV ratings via ordinal levels`() {
        // TV-14 (ordinal 4) ceiling lets PG-13 (also 4) through.
        val user = AppUser(access_level = 1, rating_ceiling = 4)
        assertTrue(user.canSeeRating("TV-14"))
        assertTrue(user.canSeeRating("PG-13"))
        // TV-MA (ordinal 5) is blocked at the same ceiling.
        assertFalse(user.canSeeRating("TV-MA"))
    }
}
