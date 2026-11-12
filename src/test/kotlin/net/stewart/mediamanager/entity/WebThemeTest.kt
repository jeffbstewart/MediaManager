package net.stewart.mediamanager.entity

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class WebThemeTest {

    @Test
    fun `ids are the persisted values and never change`() {
        // Stored in app_user.web_theme; renaming one needs a migration.
        assertEquals(listOf("marquee"), WebTheme.entries.map { it.id })
    }

    @Test
    fun `fromId resolves each known id`() {
        for (theme in WebTheme.entries) {
            assertEquals(theme, WebTheme.fromId(theme.id))
        }
    }

    @Test
    fun `fromId rejects unknown, null, blank and differently cased ids`() {
        assertNull(WebTheme.fromId(null))
        assertNull(WebTheme.fromId(""))
        assertNull(WebTheme.fromId("neon"))
        assertNull(WebTheme.fromId("MARQUEE"))
        // Retired on 2026-11-10; V103 cleared it from app_user.web_theme.
        assertNull(WebTheme.fromId("classic"))
        assertNull(WebTheme.fromId(" marquee"))
    }

    @Test
    fun `the built-in default is marquee`() {
        assertEquals(WebTheme.MARQUEE, WebTheme.DEFAULT)
    }

    @Test
    fun `the default id fits the web_theme column`() {
        // V101 declares web_theme VARCHAR(32).
        for (theme in WebTheme.entries) {
            assert(theme.id.length <= 32) { theme.id }
        }
    }

    @Test
    fun `index html carries the default as its no-JS data-theme`() {
        // The SPA paints with this attribute until main.ts reads the cache.
        val html = File("web-app/src/index.html").readText()
        val match = Regex("""<html\b[^>]*\bdata-theme="([^"]*)"""").find(html)
        assertEquals(WebTheme.DEFAULT.id, match?.groupValues?.get(1))
    }

    @Test
    fun `the SPA fallback theme and theme list match the enum`() {
        val ts = File("web-app/src/app/core/theme-ids.ts").readText()
        val fallback = Regex("""FALLBACK_THEME: ThemeId = '([^']+)'""").find(ts)
        assertEquals(WebTheme.DEFAULT.id, fallback?.groupValues?.get(1))
        val ids = Regex("""\{ id: '([^']+)', label:""").findAll(ts).map { it.groupValues[1] }.toList()
        assertEquals(WebTheme.entries.map { it.id }, ids)
    }
}
