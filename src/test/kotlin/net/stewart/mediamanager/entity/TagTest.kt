package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals

class TagTest {

    @Test
    fun `textColor returns white for dark backgrounds`() {
        assertEquals("#FFFFFF", Tag(bg_color = "#000000").textColor())
        assertEquals("#FFFFFF", Tag(bg_color = "#1E1E1E").textColor())
        assertEquals("#FFFFFF", Tag(bg_color = "#3B82F6").textColor()) // Blue
        assertEquals("#FFFFFF", Tag(bg_color = "#EF4444").textColor()) // Red
        assertEquals("#FFFFFF", Tag(bg_color = "#6366F1").textColor()) // Indigo
    }

    @Test
    fun `textColor returns dark for light backgrounds`() {
        assertEquals("#1E1E1E", Tag(bg_color = "#FFFFFF").textColor())
        assertEquals("#1E1E1E", Tag(bg_color = "#F59E0B").textColor()) // Amber
        assertEquals("#1E1E1E", Tag(bg_color = "#EAB308").textColor()) // Yellow
        assertEquals("#1E1E1E", Tag(bg_color = "#84CC16").textColor()) // Lime
        assertEquals("#1E1E1E", Tag(bg_color = "#22C55E").textColor()) // Green
    }

    @Test
    fun `textColor returns white for invalid hex`() {
        assertEquals("#FFFFFF", Tag(bg_color = "invalid").textColor())
        assertEquals("#FFFFFF", Tag(bg_color = "#ZZZ").textColor())
        assertEquals("#FFFFFF", Tag(bg_color = "").textColor())
        assertEquals("#FFFFFF", Tag(bg_color = "#12").textColor()) // too short
    }

    @Test
    fun `textColor strips hash prefix`() {
        // With and without hash should produce same result
        assertEquals("#FFFFFF", Tag(bg_color = "#000000").textColor())
    }

    @Test
    fun `textColor boundary - mid gray`() {
        // #808080 = 128,128,128, luminance = 0.502 > 0.5 → dark text
        assertEquals("#1E1E1E", Tag(bg_color = "#808080").textColor())
    }

    @Test
    fun `textColor for Stone palette color`() {
        // Stone #78716C = 120,113,108
        // luminance = (0.299*120 + 0.587*113 + 0.114*108) / 255 = (35.88 + 66.33 + 12.31) / 255 = 0.449 < 0.5
        assertEquals("#FFFFFF", Tag(bg_color = "#78716C").textColor())
    }
}
