package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleTest {

    @Test
    fun `posterUrl THUMBNAIL returns servlet URL`() {
        val title = Title(id = 42, poster_path = "/abc123.jpg")
        assertEquals("/posters/w185/42", title.posterUrl(PosterSize.THUMBNAIL))
    }

    @Test
    fun `posterUrl FULL returns servlet URL`() {
        val title = Title(id = 42, poster_path = "/abc123.jpg")
        assertEquals("/posters/w500/42", title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl returns null when poster_path is null`() {
        val title = Title(id = 42, poster_path = null)
        assertNull(title.posterUrl(PosterSize.THUMBNAIL))
        assertNull(title.posterUrl(PosterSize.FULL))
    }

    @Test
    fun `posterUrl includes title id in path`() {
        val title = Title(id = 99, poster_path = "/kMCehKisBi8MFjkDGRUzlza7VTU.jpg")
        assertEquals("/posters/w185/99", title.posterUrl(PosterSize.THUMBNAIL))
        assertEquals("/posters/w500/99", title.posterUrl(PosterSize.FULL))
    }
}
