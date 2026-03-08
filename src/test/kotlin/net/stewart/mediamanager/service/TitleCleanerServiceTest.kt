package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleCleanerServiceTest {

    // --- Bracketed tag removal ---

    @Test
    fun `strips DVD in brackets`() {
        val result = TitleCleanerService.clean("Inception [DVD]")
        assertEquals("Inception", result.displayName)
    }

    @Test
    fun `strips Blu-ray in brackets`() {
        val result = TitleCleanerService.clean("The Matrix [Blu-ray]")
        assertEquals("The Matrix", result.displayName)
    }

    @Test
    fun `strips UHD in brackets`() {
        val result = TitleCleanerService.clean("Dune [UHD]")
        assertEquals("Dune", result.displayName)
    }

    @Test
    fun `strips Region tag in brackets`() {
        val result = TitleCleanerService.clean("Amélie [Region 2]")
        assertEquals("Amélie", result.displayName)
    }

    // --- Parenthesized format removal ---

    @Test
    fun `strips DVD in parentheses`() {
        val result = TitleCleanerService.clean("Inception (DVD)")
        assertEquals("Inception", result.displayName)
    }

    @Test
    fun `strips Blu-ray Platinum Series in parentheses`() {
        val result = TitleCleanerService.clean("Golden Compas The: (Blu-ray Platinum Series)")
        assertEquals("The Golden Compas", result.displayName)
    }

    @Test
    fun `strips WS Dbl DB in parentheses`() {
        val result = TitleCleanerService.clean("Movie Title (WS/Dbl DB)")
        assertEquals("Movie Title", result.displayName)
    }

    @Test
    fun `strips Widescreen in parentheses`() {
        val result = TitleCleanerService.clean("Die Hard (Widescreen)")
        assertEquals("Die Hard", result.displayName)
    }

    @Test
    fun `strips Full Screen in parentheses`() {
        val result = TitleCleanerService.clean("Die Hard (Full Screen)")
        assertEquals("Die Hard", result.displayName)
    }

    @Test
    fun `strips P and S in parentheses`() {
        val result = TitleCleanerService.clean("Lethal Weapon (P&S)")
        assertEquals("Lethal Weapon", result.displayName)
    }

    // --- Multiple format tags ---

    @Test
    fun `strips multiple format tags in same title`() {
        val result = TitleCleanerService.clean(
            "Golden Compas The: 2-Disc Special Edition (WS/Dbl DB) (Blu-ray Platinum Series) [Blu-ray]"
        )
        assertEquals("The Golden Compas", result.displayName)
    }

    // --- Edition marker removal ---

    @Test
    fun `strips Special Edition`() {
        val result = TitleCleanerService.clean("Gladiator Special Edition")
        assertEquals("Gladiator", result.displayName)
    }

    @Test
    fun `strips Collectors Edition`() {
        val result = TitleCleanerService.clean("Blade Runner Collector's Edition")
        assertEquals("Blade Runner", result.displayName)
    }

    @Test
    fun `strips Platinum Series`() {
        val result = TitleCleanerService.clean("Movie Title Platinum Series")
        assertEquals("Movie Title", result.displayName)
    }

    @Test
    fun `strips Anniversary Edition`() {
        val result = TitleCleanerService.clean("Alien Anniversary Edition")
        assertEquals("Alien", result.displayName)
    }

    @Test
    fun `strips 2-Disc Special Edition`() {
        val result = TitleCleanerService.clean("Avatar 2-Disc Special Edition")
        assertEquals("Avatar", result.displayName)
    }

    // --- Trailing article handling ---

    @Test
    fun `moves trailing The to front`() {
        val result = TitleCleanerService.clean("Golden Compass, The")
        assertEquals("The Golden Compass", result.displayName)
    }

    @Test
    fun `moves trailing A to front`() {
        val result = TitleCleanerService.clean("Beautiful Mind, A")
        assertEquals("A Beautiful Mind", result.displayName)
    }

    @Test
    fun `moves trailing An to front`() {
        val result = TitleCleanerService.clean("Officer and a Gentleman, An")
        assertEquals("An Officer and a Gentleman", result.displayName)
    }

    @Test
    fun `trailing article with colon`() {
        val result = TitleCleanerService.clean("Batman, The:")
        assertEquals("The Batman", result.displayName)
    }

    // --- Content-distinguishing variants are kept ---

    @Test
    fun `keeps Directors Cut`() {
        val result = TitleCleanerService.clean("Blade Runner Director's Cut [Blu-ray]")
        assertEquals("Blade Runner Director's Cut", result.displayName)
    }

    @Test
    fun `keeps Unrated`() {
        val result = TitleCleanerService.clean("American Pie Unrated [DVD]")
        assertEquals("American Pie Unrated", result.displayName)
    }

    @Test
    fun `keeps Extended Edition`() {
        val result = TitleCleanerService.clean("Lord of the Rings Extended Edition [Blu-ray]")
        assertEquals("Lord of the Rings Extended Edition", result.displayName)
    }

    @Test
    fun `keeps Theatrical`() {
        val result = TitleCleanerService.clean("Alien Theatrical [Blu-ray]")
        assertEquals("Alien Theatrical", result.displayName)
    }

    // --- Sort name generation ---

    @Test
    fun `sort name strips leading The`() {
        val result = TitleCleanerService.clean("The Matrix")
        assertEquals("The Matrix", result.displayName)
        assertEquals("Matrix", result.sortName)
    }

    @Test
    fun `sort name strips leading A`() {
        val result = TitleCleanerService.clean("Beautiful Mind, A")
        assertEquals("A Beautiful Mind", result.displayName)
        assertEquals("Beautiful Mind", result.sortName)
    }

    @Test
    fun `sort name strips leading An`() {
        val result = TitleCleanerService.clean("Officer and a Gentleman, An")
        assertEquals("An Officer and a Gentleman", result.displayName)
        assertEquals("Officer and a Gentleman", result.sortName)
    }

    @Test
    fun `sort name equals display name when no article`() {
        val result = TitleCleanerService.clean("Inception")
        assertEquals("Inception", result.displayName)
        assertEquals("Inception", result.sortName)
    }

    // --- Edge cases ---

    @Test
    fun `already clean title passes through unchanged`() {
        val result = TitleCleanerService.clean("The Shawshank Redemption")
        assertEquals("The Shawshank Redemption", result.displayName)
        assertEquals("Shawshank Redemption", result.sortName)
    }

    @Test
    fun `empty string returns empty`() {
        val result = TitleCleanerService.clean("")
        assertEquals("", result.displayName)
        assertEquals("", result.sortName)
    }

    @Test
    fun `blank string returns empty`() {
        val result = TitleCleanerService.clean("   ")
        assertEquals("", result.displayName)
        assertEquals("", result.sortName)
    }

    @Test
    fun `title with only format text returns empty`() {
        val result = TitleCleanerService.clean("[Blu-ray]")
        assertEquals("", result.displayName)
    }

    @Test
    fun `multiple whitespace normalized`() {
        val result = TitleCleanerService.clean("Movie   Title    Here")
        assertEquals("Movie Title Here", result.displayName)
    }

    @Test
    fun `orphaned colon after format removal is cleaned`() {
        val result = TitleCleanerService.clean("Movie Title: (DVD)")
        assertEquals("Movie Title", result.displayName)
    }

    @Test
    fun `orphaned dash after format removal is cleaned`() {
        val result = TitleCleanerService.clean("Movie Title - [Blu-ray]")
        assertEquals("Movie Title", result.displayName)
    }
}
