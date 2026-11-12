package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Transcode
import net.stewart.transcode.ThumbnailSpriteGenerator
import org.slf4j.LoggerFactory
import java.io.File

/**
 * One-time migration: moves thumbnail sprites and subtitle files from ForBrowser
 * directories to alongside the source MKV files (the canonical location).
 *
 * Idempotent — safe to run multiple times:
 * - If files already exist alongside the source (e.g., from a prior dev run), skips them.
 * - If files exist only in ForBrowser, copies them to source and deletes the ForBrowser copies.
 * - If files exist in both locations, keeps source copy and deletes ForBrowser copy.
 *
 * Replaces the old PromoteSpritesUpdater (v1). Bump version to 2 to re-run.
 */
class MigrateAuxFilesUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(MigrateAuxFilesUpdater::class.java)

    override val name = "promote_sprites"  // Same name as old updater to continue its tracking row
    override val version = 2               // Bumped from 1 to trigger re-run

    override fun run() {
        val nasRoot = AppConfig.findAll()
            .firstOrNull { it.config_key == "nas_root_path" }
            ?.config_val
        if (nasRoot == null) {
            log.info("NAS root not configured, skipping aux file migration")
            return
        }

        val transcodes = Transcode.findAll().filter { it.file_path != null }
        var spritesMigrated = 0
        var subtitlesMigrated = 0
        var sentinelsMigrated = 0
        var fbCleaned = 0

        for (tc in transcodes) {
            try {
                val sourceFile = File(tc.file_path!!)
                if (!sourceFile.exists()) continue
                if (!TranscoderAgent.needsTranscoding(tc.file_path!!)) continue // MP4/M4V: source IS the directory

                val forBrowserMp4 = TranscoderAgent.getForBrowserPath(nasRoot, tc.file_path!!)
                if (!forBrowserMp4.exists()) continue
                val fbDir = forBrowserMp4.parentFile
                val sourceDir = sourceFile.parentFile
                val baseName = forBrowserMp4.nameWithoutExtension

                // --- Migrate thumbnail sprites ---
                val fbVtt = File(fbDir, "$baseName.thumbs.vtt")
                if (fbVtt.exists()) {
                    val sourceVtt = File(sourceDir, "$baseName.thumbs.vtt")
                    if (!sourceVtt.exists()) {
                        // Copy sprites to source directory
                        ThumbnailSpriteGenerator.copySpritesToDirectory(baseName, fbDir, sourceDir)
                        spritesMigrated++
                    }
                    // Delete ForBrowser copies (source now has them)
                    if (File(sourceDir, "$baseName.thumbs.vtt").exists()) {
                        fbVtt.delete()
                        var sheetIndex = 1
                        while (true) {
                            val sheet = File(fbDir, "$baseName.thumbs_$sheetIndex.jpg")
                            if (!sheet.exists()) break
                            sheet.delete()
                            sheetIndex++
                        }
                        fbCleaned++
                    }
                }

                // --- Migrate subtitle SRT ---
                val fbSrt = File(fbDir, "$baseName.en.srt")
                if (fbSrt.exists()) {
                    val sourceSrt = File(sourceDir, "$baseName.en.srt")
                    if (!sourceSrt.exists()) {
                        fbSrt.copyTo(sourceSrt)
                        subtitlesMigrated++
                    }
                    sourceSrt.let { if (it.exists()) { fbSrt.delete(); fbCleaned++ } }
                }

                // --- Migrate subtitle failure sentinel ---
                val fbSentinel = File(fbDir, "$baseName.en.srt.failed")
                if (fbSentinel.exists()) {
                    val sourceSentinel = File(sourceDir, "$baseName.en.srt.failed")
                    if (!sourceSentinel.exists()) {
                        fbSentinel.copyTo(sourceSentinel)
                        sentinelsMigrated++
                    }
                    sourceSentinel.let { if (it.exists()) { fbSentinel.delete(); fbCleaned++ } }
                }
            } catch (e: Exception) {
                log.warn("Failed to migrate aux files for {}: {}", tc.file_path, e.message)
            }
        }

        log.info(
            "Aux file migration complete: {} sprite sets, {} subtitles, {} sentinels migrated; {} ForBrowser copies cleaned",
            spritesMigrated, subtitlesMigrated, sentinelsMigrated, fbCleaned
        )
    }
}
