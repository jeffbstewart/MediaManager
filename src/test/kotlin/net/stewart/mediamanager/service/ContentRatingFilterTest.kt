package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.AppUser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the AppUser.canSeeRating() logic which enforces parental controls.
 *
 * Rules:
 *   - Admin always sees everything
 *   - null ceiling = unrestricted (sees everything)
 *   - Unrated titles hidden from ceiling-limited accounts
 *   - Rated titles: compare ordinal levels
 */
class ContentRatingFilterTest {

    private fun user(accessLevel: Int = 1, ratingCeiling: Int? = null) = AppUser(
        id = 1L,
        username = "test",
        display_name = "Test",
        password_hash = "",
        access_level = accessLevel,
        rating_ceiling = ratingCeiling
    )

    // --- Admin bypass ---

    @Test
    fun `admin sees everything regardless of ceiling`() {
        val admin = user(accessLevel = 2, ratingCeiling = 2) // ceiling set but ignored
        assertTrue(admin.canSeeRating("R"))
        assertTrue(admin.canSeeRating("NC-17"))
        assertTrue(admin.canSeeRating(null))
        assertTrue(admin.canSeeRating("TV-MA"))
    }

    @Test
    fun `admin sees unrated titles`() {
        val admin = user(accessLevel = 2, ratingCeiling = 0)
        assertTrue(admin.canSeeRating(null))
    }

    // --- Null ceiling (unrestricted) ---

    @Test
    fun `viewer with no ceiling sees everything`() {
        val viewer = user(accessLevel = 1, ratingCeiling = null)
        assertTrue(viewer.canSeeRating("R"))
        assertTrue(viewer.canSeeRating("NC-17"))
        assertTrue(viewer.canSeeRating("TV-MA"))
        assertTrue(viewer.canSeeRating("G"))
        assertTrue(viewer.canSeeRating(null))
    }

    // --- Unrated titles ---

    @Test
    fun `ceiling-limited viewer cannot see unrated titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 6) // max ceiling
        assertFalse(viewer.canSeeRating(null))
    }

    @Test
    fun `ceiling-limited viewer cannot see empty-string rating`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 6)
        assertFalse(viewer.canSeeRating(""))
    }

    @Test
    fun `ceiling-limited viewer cannot see unknown rating`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 6)
        assertFalse(viewer.canSeeRating("NR"))
        assertFalse(viewer.canSeeRating("Unrated"))
    }

    // --- Rated titles ---

    @Test
    fun `PG-13 ceiling allows G titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4) // PG-13 level
        assertTrue(viewer.canSeeRating("G"))
    }

    @Test
    fun `PG-13 ceiling allows PG titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertTrue(viewer.canSeeRating("PG"))
    }

    @Test
    fun `PG-13 ceiling allows PG-13 titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertTrue(viewer.canSeeRating("PG-13"))
    }

    @Test
    fun `PG-13 ceiling blocks R titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertFalse(viewer.canSeeRating("R"))
    }

    @Test
    fun `PG-13 ceiling blocks NC-17 titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertFalse(viewer.canSeeRating("NC-17"))
    }

    @Test
    fun `PG-13 ceiling allows TV-14 titles (same ordinal level)`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertTrue(viewer.canSeeRating("TV-14"))
    }

    @Test
    fun `PG-13 ceiling blocks TV-MA titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 4)
        assertFalse(viewer.canSeeRating("TV-MA"))
    }

    // --- TV ratings ---

    @Test
    fun `TV-PG ceiling allows TV-G titles`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 3) // PG/TV-PG level
        assertTrue(viewer.canSeeRating("TV-G"))
        assertTrue(viewer.canSeeRating("TV-Y"))
        assertTrue(viewer.canSeeRating("TV-Y7"))
    }

    @Test
    fun `TV-PG ceiling allows PG movie titles (same level)`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 3)
        assertTrue(viewer.canSeeRating("PG"))
    }

    @Test
    fun `TV-PG ceiling blocks TV-14`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 3)
        assertFalse(viewer.canSeeRating("TV-14"))
    }

    // --- Edge cases ---

    @Test
    fun `G ceiling allows only G and TV-G and below`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 2)
        assertTrue(viewer.canSeeRating("G"))
        assertTrue(viewer.canSeeRating("TV-G"))
        assertTrue(viewer.canSeeRating("TV-Y"))
        assertTrue(viewer.canSeeRating("TV-Y7"))
        assertFalse(viewer.canSeeRating("PG"))
        assertFalse(viewer.canSeeRating("PG-13"))
        assertFalse(viewer.canSeeRating("R"))
    }

    @Test
    fun `lowest ceiling TV-Y only allows TV-Y`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 0)
        assertTrue(viewer.canSeeRating("TV-Y"))
        assertFalse(viewer.canSeeRating("TV-Y7"))
        assertFalse(viewer.canSeeRating("G"))
    }

    @Test
    fun `R ceiling allows everything except NC-17`() {
        val viewer = user(accessLevel = 1, ratingCeiling = 5)
        assertTrue(viewer.canSeeRating("G"))
        assertTrue(viewer.canSeeRating("PG"))
        assertTrue(viewer.canSeeRating("PG-13"))
        assertTrue(viewer.canSeeRating("R"))
        assertTrue(viewer.canSeeRating("TV-MA"))
        assertFalse(viewer.canSeeRating("NC-17"))
    }
}
