package net.stewart.transcode

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Generates Roku BIF (Base Index Frame) data on-the-fly from existing thumbnail
 * sprite sheets. No disk I/O for BIF — reads sprites, returns bytes in memory.
 *
 * BIF format:
 *   - 8-byte magic: 0x89 B I F \r \n 0x1a \n
 *   - 4-byte version (uint32 LE): 0
 *   - 4-byte image count (uint32 LE)
 *   - 4-byte framewise separation in ms (uint32 LE)
 *   - 44 bytes reserved (zeros)
 *   - Index: (imageCount + 1) entries of (timestamp uint32 LE, offset uint32 LE)
 *     Last entry is sentinel: timestamp = 0xFFFFFFFF
 *   - Concatenated JPEG image data
 */
object BifGenerator {

    private val log = LoggerFactory.getLogger(BifGenerator::class.java)

    private const val THUMB_WIDTH = 160
    private const val THUMB_HEIGHT = 90
    private const val INTERVAL_SECS = 10
    private const val INTERVAL_MS = INTERVAL_SECS * 1000

    private val BIF_MAGIC = byteArrayOf(
        0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a
    )

    /**
     * Generates BIF bytes in memory from existing sprite sheets for [mp4File].
     * Returns null if no sprite sheets exist (thumbnails must be generated first).
     */
    fun generateBytes(mp4File: File): ByteArray? {
        val baseName = mp4File.nameWithoutExtension
        val parentDir = mp4File.parentFile
        val vttFile = File(parentDir, "$baseName.thumbs.vtt")

        if (!vttFile.exists()) return null

        // Find all sprite sheet JPGs in order
        val spriteSheets = mutableListOf<File>()
        var sheetIndex = 1
        while (true) {
            val sheet = File(parentDir, "$baseName.thumbs_$sheetIndex.jpg")
            if (!sheet.exists()) break
            spriteSheets.add(sheet)
            sheetIndex++
        }

        if (spriteSheets.isEmpty()) return null

        try {
            // Extract individual frames from sprite grids
            val frameJpegs = mutableListOf<ByteArray>()

            for ((idx, sheetFile) in spriteSheets.withIndex()) {
                val sheetImage = ImageIO.read(sheetFile) ?: run {
                    log.warn("Failed to read sprite sheet: {}", sheetFile.name)
                    return null
                }

                val sheetCols = sheetImage.width / THUMB_WIDTH
                val sheetRows = sheetImage.height / THUMB_HEIGHT
                val isLastSheet = idx == spriteSheets.size - 1

                for (row in 0 until sheetRows) {
                    for (col in 0 until sheetCols) {
                        val x = col * THUMB_WIDTH
                        val y = row * THUMB_HEIGHT

                        if (x + THUMB_WIDTH > sheetImage.width || y + THUMB_HEIGHT > sheetImage.height) continue

                        val tile = sheetImage.getSubimage(x, y, THUMB_WIDTH, THUMB_HEIGHT)

                        // Skip blank tiles on the last sheet (black/empty padding)
                        if (isLastSheet && isBlankTile(tile)) continue

                        val baos = ByteArrayOutputStream(8192)
                        ImageIO.write(tile, "jpg", baos)
                        frameJpegs.add(baos.toByteArray())
                    }
                }
            }

            if (frameJpegs.isEmpty()) return null

            return packBif(frameJpegs)
        } catch (e: Exception) {
            log.error("BIF generation failed for {}: {}", mp4File.name, e.message)
            return null
        }
    }

    /**
     * Returns true if sprite sheets exist for the given MP4 (meaning BIF can be generated).
     */
    fun hasSprites(mp4File: File): Boolean {
        val vttFile = File(mp4File.parentFile, "${mp4File.nameWithoutExtension}.thumbs.vtt")
        return vttFile.exists()
    }

    /**
     * Detects blank (fully black) tiles that are padding on the last sprite sheet.
     */
    private fun isBlankTile(tile: BufferedImage): Boolean {
        val samplePoints = listOf(
            THUMB_WIDTH / 4 to THUMB_HEIGHT / 4,
            THUMB_WIDTH / 2 to THUMB_HEIGHT / 2,
            3 * THUMB_WIDTH / 4 to 3 * THUMB_HEIGHT / 4
        )
        for ((x, y) in samplePoints) {
            val rgb = tile.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            if (r > 10 || g > 10 || b > 10) return false
        }
        return true
    }

    private fun packBif(frameJpegs: List<ByteArray>): ByteArray {
        val imageCount = frameJpegs.size
        val headerSize = 64
        val indexSize = (imageCount + 1) * 8
        val dataOffset = headerSize + indexSize

        // Calculate total size
        val totalDataSize = frameJpegs.sumOf { it.size }
        val totalSize = dataOffset + totalDataSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buf.put(BIF_MAGIC)
        buf.putInt(0)  // version
        buf.putInt(imageCount)
        buf.putInt(INTERVAL_MS)
        // 44 bytes reserved (already zeroed)
        buf.position(buf.position() + 44)

        // Index entries
        var currentOffset = dataOffset
        for (i in 0 until imageCount) {
            buf.putInt(i)  // timestamp (frame index, multiplied by separation by Roku)
            buf.putInt(currentOffset)
            currentOffset += frameJpegs[i].size
        }
        // Sentinel
        buf.putInt(-1)  // 0xFFFFFFFF
        buf.putInt(currentOffset)

        // Image data
        for (data in frameJpegs) {
            buf.put(data)
        }

        return buf.array()
    }
}
