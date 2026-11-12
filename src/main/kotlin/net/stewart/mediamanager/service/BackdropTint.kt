package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The hero tint of a backdrop (docs/plans/marquee-reference/STYLE-SPEC.md
 * 3.4): the image's saturated average colour, pinned to about 20%
 * lightness so white text over it always has contrast. A hero banner
 * fills with the tint and fades it into the backdrop, which is what gives
 * each banner its own colour.
 *
 * Pure: [compute] reads pixels and returns `#rrggbb`. Callers store the
 * result on the title row and in the image's sidecar; nothing here
 * touches the database.
 */
object BackdropTint {
    private val log = LoggerFactory.getLogger(BackdropTint::class.java)

    /** Neutral tint for a backdrop that is missing, undecodable, or grey. */
    const val FALLBACK = "#2a2a2a"

    /** Lightness (HSL) of every computed tint. */
    private const val LIGHTNESS = 0.20

    /** Saturation (HSL) is clamped here so the tint reads as a colour without shouting. */
    private const val MIN_SATURATION = 0.45
    private const val MAX_SATURATION = 0.85

    /**
     * Below this mean colour weight the image is effectively grey (noir,
     * black and white, fog) and gets [FALLBACK] rather than a hue picked
     * out of compression noise.
     */
    private const val MIN_MEAN_WEIGHT = 0.02

    /** Samples across the long edge; a 1280px backdrop reads about 16k pixels. */
    private const val SAMPLES_PER_EDGE = 128

    /**
     * Saturated average colour of [image] at [LIGHTNESS].
     *
     * Each sampled pixel votes for its hue with a weight of saturation
     * times chroma, so vivid pixels dominate and black bars, white skies,
     * and grey asphalt count for nothing. The hue is the circular mean of
     * those votes; the saturation is their weighted mean, clamped.
     */
    fun compute(image: BufferedImage): String {
        val step = max(1, max(image.width, image.height) / SAMPLES_PER_EDGE)
        var x = 0.0
        var y = 0.0
        var weight = 0.0
        var saturation = 0.0
        var samples = 0
        var py = 0
        while (py < image.height) {
            var px = 0
            while (px < image.width) {
                val rgb = image.getRGB(px, py)
                val r = (rgb shr 16 and 0xff) / 255.0
                val g = (rgb shr 8 and 0xff) / 255.0
                val b = (rgb and 0xff) / 255.0
                samples++
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                val chroma = hi - lo
                if (chroma > 1e-6) {
                    val l = (hi + lo) / 2
                    val s = chroma / (1 - abs(2 * l - 1))
                    val w = s * chroma
                    val h = hueRadians(r, g, b, hi, chroma)
                    x += cos(h) * w
                    y += sin(h) * w
                    weight += w
                    saturation += s * w
                }
                px += step
            }
            py += step
        }
        if (samples == 0 || weight / samples < MIN_MEAN_WEIGHT) return FALLBACK
        var hue = Math.toDegrees(atan2(y, x))
        if (hue < 0) hue += 360.0
        val s = (saturation / weight).coerceIn(MIN_SATURATION, MAX_SATURATION)
        return hslToHex(hue, s, LIGHTNESS)
    }

    /** [compute] over encoded image bytes; null when they do not decode. */
    fun fromBytes(bytes: ByteArray): String? =
        try {
            ImageIO.read(ByteArrayInputStream(bytes))?.let { compute(it) }
        } catch (e: Exception) {
            log.debug("Backdrop tint: decode failed: {}", e.message)
            null
        }

    /** [compute] over an image file; null when it is missing or does not decode. */
    fun fromFile(path: Path): String? =
        try {
            if (!Files.isRegularFile(path)) null
            else Files.newInputStream(path).use { ImageIO.read(it) }?.let { compute(it) }
        } catch (e: Exception) {
            log.debug("Backdrop tint: decode failed for {}: {}", path, e.message)
            null
        }

    private fun hueRadians(r: Double, g: Double, b: Double, hi: Double, chroma: Double): Double {
        val h = when (hi) {
            r -> ((g - b) / chroma).mod(6.0)
            g -> (b - r) / chroma + 2
            else -> (r - g) / chroma + 4
        }
        return Math.toRadians(h * 60.0)
    }

    /** HSL (hue in degrees, s and l in 0..1) to `#rrggbb`. */
    internal fun hslToHex(hue: Double, s: Double, l: Double): String {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = hue / 60.0
        val xx = c * (1 - abs(hp.mod(2.0) - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, xx, 0.0)
            hp < 2 -> Triple(xx, c, 0.0)
            hp < 3 -> Triple(0.0, c, xx)
            hp < 4 -> Triple(0.0, xx, c)
            hp < 5 -> Triple(xx, 0.0, c)
            else -> Triple(c, 0.0, xx)
        }
        val m = l - c / 2
        fun channel(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(channel(r1), channel(g1), channel(b1))
    }
}
