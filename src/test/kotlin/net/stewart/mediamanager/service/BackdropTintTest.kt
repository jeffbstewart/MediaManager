package net.stewart.mediamanager.service

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BackdropTint.compute] on synthetic images: the dominant hue wins,
 * black bars and grey count for nothing, grey images fall back, and the
 * result is always dark enough for white text.
 */
class BackdropTintTest {

    private fun image(width: Int = 64, height: Int = 36, paint: (x: Int, y: Int) -> Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, paint(x, y).rgb)
        return img
    }

    private fun solid(color: Color) = image { _, _ -> color }

    private data class Rgb(val r: Int, val g: Int, val b: Int) {
        val lightness get() = (maxOf(r, g, b) + minOf(r, g, b)) / 2.0 / 255.0
        val luminance: Double get() {
            fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }
    }

    private fun parse(hex: String): Rgb {
        assertTrue(Regex("^#[0-9a-f]{6}$").matches(hex), "well-formed #rrggbb: $hex")
        return Rgb(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))
    }

    @Test
    fun `a red image gives a dark red`() {
        val c = parse(BackdropTint.compute(solid(Color(220, 30, 40))))
        assertTrue(c.r > c.g && c.r > c.b, "red dominates: $c")
        assertEquals(0.20, c.lightness, 0.03)
    }

    @Test
    fun `a blue image gives a dark blue`() {
        val c = parse(BackdropTint.compute(solid(Color(20, 60, 200))))
        assertTrue(c.b > c.r && c.b > c.g, "blue dominates: $c")
        assertEquals(0.20, c.lightness, 0.03)
    }

    @Test
    fun `a grey image falls back to the neutral tint`() {
        val gradient = image { x, _ -> val v = x * 4; Color(v, v, v) }
        assertEquals(BackdropTint.FALLBACK, BackdropTint.compute(gradient))
        assertEquals(BackdropTint.FALLBACK, BackdropTint.compute(solid(Color.BLACK)))
        assertEquals(BackdropTint.FALLBACK, BackdropTint.compute(solid(Color.WHITE)))
    }

    @Test
    fun `the more saturated area wins over a larger washed-out one`() {
        // 70% of pixels pale red, 30% vivid blue: the vivid blue should carry the hue.
        val mixed = image { x, _ -> if (x < 45) Color(200, 170, 170) else Color(10, 30, 220) }
        val c = parse(BackdropTint.compute(mixed))
        assertTrue(c.b > c.r, "vivid blue outweighs pale red: $c")
    }

    @Test
    fun `black letterbox bars do not dilute the colour`() {
        val boxed = image(64, 36) { _, y -> if (y < 8 || y >= 28) Color.BLACK else Color(30, 170, 60) }
        val c = parse(BackdropTint.compute(boxed))
        assertTrue(c.g > c.r && c.g > c.b, "green dominates: $c")
        assertEquals(0.20, c.lightness, 0.03)
    }

    @Test
    fun `every tint is dark enough for white text`() {
        // Spec: about 20% lightness. The darkest-acceptable bound for white
        // body text is a relative luminance well under 0.18 (4.5:1).
        for (hue in 0 until 360 step 30) {
            val rgb = Color.getHSBColor(hue / 360f, 0.9f, 0.9f)
            val c = parse(BackdropTint.compute(solid(rgb)))
            assertTrue(c.luminance < 0.12, "hue $hue luminance ${c.luminance} from $c")
        }
    }

    @Test
    fun `hsl conversion hits the primaries`() {
        assertEquals("#ff0000", BackdropTint.hslToHex(0.0, 1.0, 0.5))
        assertEquals("#00ff00", BackdropTint.hslToHex(120.0, 1.0, 0.5))
        assertEquals("#0000ff", BackdropTint.hslToHex(240.0, 1.0, 0.5))
        assertEquals("#333333", BackdropTint.hslToHex(0.0, 0.0, 0.2))
    }

    @Test
    fun `fromBytes decodes a jpeg and rejects garbage`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(solid(Color(200, 40, 40)), "jpg", out)
        val tint = BackdropTint.fromBytes(out.toByteArray())
        assertNotEquals(null, tint)
        assertNotEquals(BackdropTint.FALLBACK, tint)
        assertNull(BackdropTint.fromBytes(byteArrayOf(1, 2, 3, 4)))
    }
}
