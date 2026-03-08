package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Transcode
import net.stewart.transcode.ThumbnailSpriteGenerator
import org.slf4j.LoggerFactory
import java.io.File

/**
 * One-time backfill: copies ForBrowser thumbnail sprites to source directories.
 * Idempotent — skips files that already exist in the source directory.
 */
class PromoteSpritesUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PromoteSpritesUpdater::class.java)

    override val name = "promote_sprites"
    override val version = 1

    override fun run() {
        val nasRoot = AppConfig.findAll()
            .firstOrNull { it.config_key == "nas_root_path" }
            ?.config_val
        if (nasRoot == null) {
            log.info("NAS root not configured, skipping sprite promotion")
            return
        }

        val transcodes = Transcode.findAll().filter { it.file_path != null }
        var promoted = 0
        var skipped = 0

        for (tc in transcodes) {
            try {
                val sourceFile = File(tc.file_path!!)
                if (!sourceFile.exists()) continue

                val forBrowserMp4 = TranscoderAgent.getForBrowserPath(nasRoot, tc.file_path!!)
                if (!forBrowserMp4.exists()) continue

                val baseName = forBrowserMp4.nameWithoutExtension
                val vttFile = File(forBrowserMp4.parentFile, "$baseName.thumbs.vtt")
                if (!vttFile.exists()) { skipped++; continue }

                // Skip if source already has the VTT and it's up to date
                if (sourceFile.parentFile == forBrowserMp4.parentFile) continue
                val sourceVtt = File(sourceFile.parentFile, "$baseName.thumbs.vtt")
                if (sourceVtt.exists() && sourceVtt.lastModified() >= vttFile.lastModified()) continue

                val copied = ThumbnailSpriteGenerator.copySpritesToDirectory(
                    baseName, forBrowserMp4.parentFile, sourceFile.parentFile
                )
                if (copied > 0) promoted++
            } catch (e: Exception) {
                log.warn("Failed to promote sprites for {}: {}", tc.file_path, e.message)
            }
        }

        log.info("Sprite promotion complete: {} set(s) promoted, {} skipped (no ForBrowser sprites)", promoted, skipped)
    }
}
