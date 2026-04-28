package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WishListItemTest {

    // WishListItem.tmdbKey delegates to TmdbId.of, which already has
    // its own test. The branches we need to exercise here are the
    // entity-shaped wiring: tmdb_id can be null (TRANSCODE-typed
    // wishes don't have one), tmdb_media_type can be MOVIE / TV /
    // PERSONAL / etc.

    @Test
    fun `tmdbKey returns key for movie wish`() {
        val w = WishListItem(tmdb_id = 603, tmdb_media_type = MediaType.MOVIE.name)
        val key = w.tmdbKey()
        assertNotNull(key)
        assertEquals(603, key.id)
        assertEquals(MediaType.MOVIE, key.type)
    }

    @Test
    fun `tmdbKey returns key for tv wish`() {
        val w = WishListItem(tmdb_id = 1399, tmdb_media_type = MediaType.TV.name)
        val key = w.tmdbKey()
        assertNotNull(key)
        assertEquals(MediaType.TV, key.type)
    }

    @Test
    fun `tmdbKey returns null when tmdb_id missing`() {
        // TRANSCODE / BOOK / ALBUM wish rows often have no tmdb_id —
        // they're keyed on title_id / ol_work_id / release_group_id
        // instead. tmdbKey should return null cleanly.
        val w = WishListItem(tmdb_id = null, tmdb_media_type = MediaType.MOVIE.name)
        assertNull(w.tmdbKey())
    }

    @Test
    fun `tmdbKey returns null when tmdb_media_type unknown`() {
        val w = WishListItem(tmdb_id = 100, tmdb_media_type = "BOGUS")
        assertNull(w.tmdbKey())
    }
}
