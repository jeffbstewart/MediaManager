package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscodeFileParserTest {

    // --- Movie parsing ---

    @Test
    fun `simple movie name`() {
        val result = TranscodeFileParser.parseMovieFile("Inception.mkv")
        assertEquals("Inception", result.title)
        assertNull(result.year)
        assertFalse(result.isEpisode)
    }

    @Test
    fun `movie with sequel number in parens kept`() {
        val result = TranscodeFileParser.parseMovieFile("Die Hard (1).mkv")
        assertEquals("Die Hard (1)", result.title)
        assertNull(result.year)
    }

    @Test
    fun `movie with trailing article normalized`() {
        val result = TranscodeFileParser.parseMovieFile("Karate Kid The (1984).mkv")
        assertEquals("The Karate Kid", result.title)
        assertEquals(1984, result.year)
    }

    @Test
    fun `movie with MakeMKV suffix stripped`() {
        val result = TranscodeFileParser.parseMovieFile("Schindler's List_t02.mkv")
        assertEquals("Schindler's List", result.title)
        assertNull(result.year)
    }

    @Test
    fun `movie with trailing whitespace before extension`() {
        val result = TranscodeFileParser.parseMovieFile("Airplane .mkv")
        assertEquals("Airplane", result.title)
        assertNull(result.year)
    }

    @Test
    fun `movie with year extracted`() {
        val result = TranscodeFileParser.parseMovieFile("Blade Runner (1982).mkv")
        assertEquals("Blade Runner", result.title)
        assertEquals(1982, result.year)
    }

    @Test
    fun `movie with mp4 extension`() {
        val result = TranscodeFileParser.parseMovieFile("Avatar (2009).mp4")
        assertEquals("Avatar", result.title)
        assertEquals(2009, result.year)
    }

    @Test
    fun `movie small number in parens not treated as year`() {
        val result = TranscodeFileParser.parseMovieFile("Rocky (3).mkv")
        assertEquals("Rocky (3)", result.title)
        assertNull(result.year)
    }

    // --- TV parsing ---

    @Test
    fun `standard TV episode format`() {
        val result = TranscodeFileParser.parseTvEpisodeFile("Breaking Bad - S01E01 - Pilot.mkv")
        assertEquals("Breaking Bad", result.title)
        assertEquals(1, result.seasonNumber)
        assertEquals(1, result.episodeNumber)
        assertEquals("Pilot", result.episodeTitle)
        assertTrue(result.isEpisode)
    }

    @Test
    fun `doubled TV format with repeated show and episode`() {
        val result = TranscodeFileParser.parseTvEpisodeFile(
            "Cheers - S01E01 - Cheers - s01e01 - Give Me a Ring Sometime.mkv"
        )
        assertEquals("Cheers", result.title)
        assertEquals(1, result.seasonNumber)
        assertEquals(1, result.episodeNumber)
        assertEquals("Give Me a Ring Sometime", result.episodeTitle)
        assertTrue(result.isEpisode)
    }

    @Test
    fun `TV episode with year in show name`() {
        val result = TranscodeFileParser.parseTvEpisodeFile(
            "Star Trek Discovery (2017) - S01E01 - The Vulcan Hello.mkv"
        )
        assertEquals("Star Trek Discovery", result.title)
        assertEquals(2017, result.year)
        assertEquals(1, result.seasonNumber)
        assertEquals(1, result.episodeNumber)
        assertEquals("The Vulcan Hello", result.episodeTitle)
    }

    @Test
    fun `TV episode with lowercase s and e`() {
        val result = TranscodeFileParser.parseTvEpisodeFile(
            "Miami Vice - s01e01 - Brother's Keeper.mkv"
        )
        assertEquals("Miami Vice", result.title)
        assertEquals(1, result.seasonNumber)
        assertEquals(1, result.episodeNumber)
        assertEquals("Brother's Keeper", result.episodeTitle)
    }

    @Test
    fun `TV episode without episode title`() {
        val result = TranscodeFileParser.parseTvEpisodeFile("The Office - S02E05.mkv")
        assertEquals("The Office", result.title)
        assertEquals(2, result.seasonNumber)
        assertEquals(5, result.episodeNumber)
        assertNull(result.episodeTitle)
    }

    @Test
    fun `TV episode multi-digit season and episode`() {
        val result = TranscodeFileParser.parseTvEpisodeFile(
            "Supernatural - S15E20 - Carry On.mkv"
        )
        assertEquals("Supernatural", result.title)
        assertEquals(15, result.seasonNumber)
        assertEquals(20, result.episodeNumber)
        assertEquals("Carry On", result.episodeTitle)
    }
}
