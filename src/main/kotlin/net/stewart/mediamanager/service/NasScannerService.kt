package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.CommandLineFlags
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

object NasScannerService {

    private val log = LoggerFactory.getLogger(NasScannerService::class.java)
    private val running = AtomicBoolean(false)

    private fun countScan(result: String) {
        MetricsRegistry.registry.counter("mm_nas_scans_total", "result", result).increment()
    }
    private val filesMatchedCounter = MetricsRegistry.registry.counter("mm_nas_scan_files_matched_total")
    private val filesUnmatchedCounter = MetricsRegistry.registry.counter("mm_nas_scan_files_unmatched_total")

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v")

    /** Regex to detect TV episode patterns in filenames (e.g., S01E02, s1e3). */
    private val TV_EPISODE_PATTERN = Regex("""[Ss]\d{1,2}[Ee]\d{1,2}""")

    fun isRunning(): Boolean = running.get()

    fun scan() {
        if (!running.compareAndSet(false, true)) {
            log.warn("NAS scan already in progress, skipping")
            return
        }

        Thread({
            try {
                doScan()
            } catch (e: Exception) {
                log.error("NAS scan failed", e)
                countScan("failed")
                Broadcaster.broadcastNasScan(NasScanProgress(
                    phase = "FAILED", message = "Scan failed: ${e.message}"
                ))
            } finally {
                running.set(false)
            }
        }, "nas-scanner").apply {
            isDaemon = true
            start()
        }
    }

    private fun doScan() {
        val nasRoot = getNasRootPath()
        if (nasRoot == null) {
            log.warn("NAS scan aborted: nas_root_path not configured. " +
                "Set it via Transcodes > Settings or insert into app_config.")
            Broadcaster.broadcastNasScan(NasScanProgress(
                phase = "FAILED",
                message = "NAS root path not configured — open Settings (gear icon) to set it"
            ))
            return
        }

        val rootPath = Path.of(nasRoot)
        if (!Files.isDirectory(rootPath)) {
            log.warn("NAS scan aborted: root path is not accessible: {}", nasRoot)
            Broadcaster.broadcastNasScan(NasScanProgress(
                phase = "FAILED", message = "NAS root path not accessible: $nasRoot — check the path and network connectivity"
            ))
            return
        }

        // Phase 0: Sanitize filenames with Windows-disallowed characters
        Broadcaster.broadcastNasScan(NasScanProgress(phase = "SANITIZING", message = "Checking for Windows-incompatible filenames..."))
        val renames = FilenameSanitizer.fixupNasFiles(nasRoot)
        if (renames.isNotEmpty()) {
            log.info("Sanitized {} filenames with Windows-disallowed characters", renames.size)
            // Update any existing DB records that reference the old paths
            updateRenamedPaths(renames)
        }

        // Ensure managed directories exist with .mm-ignore markers
        ManagedDirectoryService.ensureManagedDirectories()

        Broadcaster.broadcastNasScan(NasScanProgress(phase = "SCANNING", message = "Discovering files..."))

        // Phase 1: Discover files — auto-classify top-level directories
        val discovered = mutableListOf<DiscoveredFileCandidate>()

        val topLevelDirs = Files.list(rootPath).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { !ManagedDirectoryService.isIgnored(it.toFile()) }
                .toList()
        }

        val personalVideoDir = getPersonalVideoDir()

        // Reclassify existing discovered files from the personal video directory
        if (personalVideoDir != null) {
            val reclassified = DiscoveredFile.findAll()
                .filter { it.directory.equals(personalVideoDir, ignoreCase = true) }
                .filter { it.media_type != MediaType.PERSONAL.name }
            if (reclassified.isNotEmpty()) {
                for (df in reclassified) {
                    df.media_type = MediaType.PERSONAL.name
                    df.save()
                }
                log.info("Reclassified {} existing discovered files from '{}' as PERSONAL",
                    reclassified.size, personalVideoDir)
            }
        }

        for (dir in topLevelDirs) {
            // Personal video directory: classify all files as PERSONAL, discover recursively
            if (personalVideoDir != null && dir.fileName.toString().equals(personalVideoDir, ignoreCase = true)) {
                log.info("Directory '{}' classified as PERSONAL (configured personal_video_nas_dir)", dir.fileName)
                discoverRecursive(dir, MediaType.PERSONAL.name, discovered)
                continue
            }

            val classification = classifyDirectory(dir)
            log.info("Directory '{}' classified as {}", dir.fileName, classification)
            when (classification) {
                DirectoryType.TV -> discoverRecursive(dir, MediaType.TV.name, discovered)
                DirectoryType.MOVIE -> discoverFlat(dir, MediaType.MOVIE.name, discovered)
                DirectoryType.EMPTY -> {} // skip
            }
        }

        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "SCANNING", filesFound = discovered.size,
            message = "Found ${discovered.size} files, deduplicating..."
        ))

        // Phase 2: Dedup — skip files already tracked
        val allTranscodes = Transcode.findAll()
        val existingTranscodePaths = allTranscodes.mapNotNull { it.file_path }.toSet()
        val existingDiscoveredPaths = DiscoveredFile.findAll().map { it.file_path }.toSet()
        val knownPaths = existingTranscodePaths + existingDiscoveredPaths

        val newFiles = discovered.filter { it.filePath !in knownPaths }

        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "MATCHING", filesFound = discovered.size,
            message = "Matching ${newFiles.size} new files..."
        ))

        // Phase 3: Parse and Match
        val titles = Title.findAll()
        val titleById = titles.associateBy { it.id }

        // Movie titles that already have a transcode — guard against linking episodes to movies
        val movieTitlesWithTranscode = allTranscodes
            .filter { tc -> titleById[tc.title_id]?.media_type == MediaType.MOVIE.name }
            .map { it.title_id }
            .toMutableSet()

        var matchedCount = 0
        var unmatchedCount = 0
        val now = LocalDateTime.now()

        for (candidate in newFiles) {
            try {
                val matched = processCandidate(candidate, titles, titleById, movieTitlesWithTranscode, now)
                if (matched) matchedCount++ else unmatchedCount++
            } catch (e: Exception) {
                log.error("Error processing file '{}': {}", candidate.fileName, e.message)
                unmatchedCount++
            }
        }

        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "CLEANUP", filesFound = discovered.size,
            matched = matchedCount, unmatched = unmatchedCount,
            message = "Checking for deleted files..."
        ))

        // Phase 4: Cleanup deleted files
        val deletedCount = cleanupDeletedFiles()

        // Phase 5: Import .skip.json files from external skip detection agents
        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "SKIP_IMPORT", message = "Importing skip detection data..."
        ))
        val skipImported = importSkipFiles()
        if (skipImported > 0) {
            log.info("Imported {} skip segment(s) from external agents", skipImported)
        }

        // Phase 6: Refresh TV season ownership based on transcode+episode data
        try {
            MissingSeasonService.refreshOwnership()
        } catch (e: Exception) {
            log.warn("Season ownership refresh failed: {}", e.message)
        }

        // Phase 7: Promote ForBrowser sprites to source directories
        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "SPRITES", message = "Promoting thumbnail sprites to source directories..."
        ))
        val spritesPromoted = promoteSpritesToSource(rootPath)
        if (spritesPromoted > 0) {
            log.info("Promoted {} sprite set(s) to source directories", spritesPromoted)
        }

        // Phase 8: Probe unknown media formats in background
        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "PROBING", message = "Probing unknown media formats..."
        ))
        val probed = FormatProbeService.probeUnknownFormats()
        if (probed > 0) {
            log.info("Probed format for {} files", probed)
        }

        countScan("success")
        filesMatchedCounter.increment(matchedCount.toDouble())
        filesUnmatchedCounter.increment(unmatchedCount.toDouble())

        Broadcaster.broadcastNasScan(NasScanProgress(
            phase = "COMPLETE", filesFound = discovered.size,
            matched = matchedCount, unmatched = unmatchedCount, deleted = deletedCount,
            message = "Scan complete: ${newFiles.size} new files ($matchedCount matched, $unmatchedCount unmatched), $deletedCount deleted"
        ))
    }

    private fun discoverFlat(dir: Path, mediaType: String, out: MutableList<DiscoveredFileCandidate>) {
        try {
            Files.list(dir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path) && !Files.isSymbolicLink(path) && isVideoFile(path)) {
                        out.add(DiscoveredFileCandidate(
                            filePath = path.toString(),
                            fileName = path.fileName.toString(),
                            directory = dir.fileName.toString(),
                            fileSizeBytes = Files.size(path),
                            mediaType = mediaType,
                            fileModifiedAt = readFileModifiedAt(path)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Error listing directory {}: {}", dir, e.message)
        }
    }

    private fun discoverRecursive(dir: Path, mediaType: String, out: MutableList<DiscoveredFileCandidate>) {
        try {
            Files.walk(dir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path) && !Files.isSymbolicLink(path) && isVideoFile(path)
                        && !ManagedDirectoryService.isIgnored(path.parent.toFile())) {
                        out.add(DiscoveredFileCandidate(
                            filePath = path.toString(),
                            fileName = path.fileName.toString(),
                            directory = dir.fileName.toString(),
                            fileSizeBytes = Files.size(path),
                            mediaType = mediaType,
                            fileModifiedAt = readFileModifiedAt(path)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Error walking directory {}: {}", dir, e.message)
        }
    }

    /**
     * Classifies a top-level directory as TV or Movie based on structure.
     *
     * - If media files are found inside subdirectories (depth >= 2) → TV
     * - If media files sit directly in the directory (depth 1) → MOVIE
     * - SxxExx patterns in any filename override to TV regardless of depth
     */
    private fun classifyDirectory(dir: Path): DirectoryType {
        var hasDirectFiles = false
        var hasNestedFiles = false
        var hasEpisodePattern = false

        try {
            Files.walk(dir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path) && isVideoFile(path)
                        && !ManagedDirectoryService.isIgnored(path.parent.toFile())) {
                        val depth = dir.relativize(path).nameCount
                        if (depth == 1) hasDirectFiles = true
                        if (depth >= 2) hasNestedFiles = true
                        if (TV_EPISODE_PATTERN.containsMatchIn(path.fileName.toString())) {
                            hasEpisodePattern = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Error classifying directory {}: {}", dir, e.message)
        }

        return when {
            hasEpisodePattern -> DirectoryType.TV
            hasNestedFiles -> DirectoryType.TV
            hasDirectFiles -> DirectoryType.MOVIE
            else -> DirectoryType.EMPTY
        }
    }

    private enum class DirectoryType { MOVIE, TV, EMPTY }

    private fun readFileModifiedAt(path: Path): LocalDateTime? {
        return try {
            val instant = Files.getLastModifiedTime(path).toInstant()
            LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }

    private fun isVideoFile(path: Path): Boolean {
        val ext = path.extension.lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun findOrCreateEpisode(titleId: Long, season: Int, episode: Int, episodeTitle: String?): Long {
        val existing = Episode.findAll().firstOrNull {
            it.title_id == titleId && it.season_number == season && it.episode_number == episode
        }
        if (existing != null) return existing.id!!

        val ep = Episode(
            title_id = titleId,
            season_number = season,
            episode_number = episode,
            name = episodeTitle
        )
        ep.save()
        return ep.id!!
    }

    /**
     * Processes a single discovered file: parses, matches, and creates DB records.
     * Returns true if matched, false if unmatched.
     */
    private fun processCandidate(
        candidate: DiscoveredFileCandidate,
        titles: List<Title>,
        titleById: Map<Long?, Title>,
        movieTitlesWithTranscode: MutableSet<Long>,
        now: LocalDateTime
    ): Boolean {
        val parsed = if (candidate.mediaType == MediaType.TV.name) {
            TranscodeFileParser.parseTvEpisodeFile(candidate.fileName)
        } else {
            TranscodeFileParser.parseMovieFile(candidate.fileName)
        }

        // Personal videos are never auto-matched; they require manual title creation
        if (candidate.mediaType == MediaType.PERSONAL.name) {
            DiscoveredFile(
                file_path = candidate.filePath,
                file_name = candidate.fileName,
                directory = candidate.directory,
                file_size_bytes = candidate.fileSizeBytes,
                media_format = MediaFormat.UNKNOWN.name,
                media_type = candidate.mediaType,
                parsed_title = parsed.title,
                parsed_year = parsed.year,
                match_status = DiscoveredFileStatus.UNMATCHED.name,
                file_modified_at = candidate.fileModifiedAt,
                discovered_at = now
            ).save()
            return false
        }

        val matchResult = if (candidate.mediaType == MediaType.TV.name) {
            TranscodeMatcherService.matchTvShow(parsed.title, titles)
        } else {
            TranscodeMatcherService.matchMovie(parsed, titles)
        }

        // Guard: don't auto-link a second transcode to a movie (likely a mismatched TV episode)
        val effectiveMatch = if (matchResult.titleId != null &&
            matchResult.titleId in movieTitlesWithTranscode) {
            log.info("Skipping auto-link of '{}' to movie title id={} — movie already has a transcode",
                candidate.fileName, matchResult.titleId)
            MatchResult()
        } else {
            matchResult
        }

        if (effectiveMatch.titleId != null) {
            // Matched — create Transcode + DiscoveredFile as MATCHED
            var episodeId: Long? = null
            if (parsed.isEpisode && parsed.seasonNumber != null && parsed.episodeNumber != null) {
                episodeId = findOrCreateEpisode(
                    effectiveMatch.titleId, parsed.seasonNumber, parsed.episodeNumber,
                    parsed.episodeTitle
                )
            }

            Transcode(
                title_id = effectiveMatch.titleId,
                episode_id = episodeId,
                file_path = candidate.filePath,
                file_size_bytes = candidate.fileSizeBytes,
                file_modified_at = candidate.fileModifiedAt,
                status = TranscodeStatus.COMPLETE.name,
                media_format = MediaFormat.UNKNOWN.name,
                match_method = effectiveMatch.method?.name,
                created_at = now,
                updated_at = now
            ).save()

            // Track this movie so subsequent files in the same scan batch are also guarded
            if (titleById[effectiveMatch.titleId]?.media_type == MediaType.MOVIE.name) {
                movieTitlesWithTranscode.add(effectiveMatch.titleId)
            }

            DiscoveredFile(
                file_path = candidate.filePath,
                file_name = candidate.fileName,
                directory = candidate.directory,
                file_size_bytes = candidate.fileSizeBytes,
                media_format = MediaFormat.UNKNOWN.name,
                media_type = candidate.mediaType,
                parsed_title = parsed.title,
                parsed_year = parsed.year,
                parsed_season = parsed.seasonNumber,
                parsed_episode = parsed.episodeNumber,
                parsed_episode_title = parsed.episodeTitle,
                match_status = DiscoveredFileStatus.MATCHED.name,
                matched_title_id = effectiveMatch.titleId,
                matched_episode_id = episodeId,
                match_method = effectiveMatch.method?.name,
                file_modified_at = candidate.fileModifiedAt,
                discovered_at = now
            ).save()

            return true
        } else {
            // Unmatched — park in discovered_file
            DiscoveredFile(
                file_path = candidate.filePath,
                file_name = candidate.fileName,
                directory = candidate.directory,
                file_size_bytes = candidate.fileSizeBytes,
                media_format = MediaFormat.UNKNOWN.name,
                media_type = candidate.mediaType,
                parsed_title = parsed.title,
                parsed_year = parsed.year,
                parsed_season = parsed.seasonNumber,
                parsed_episode = parsed.episodeNumber,
                parsed_episode_title = parsed.episodeTitle,
                match_status = DiscoveredFileStatus.UNMATCHED.name,
                file_modified_at = candidate.fileModifiedAt,
                discovered_at = now
            ).save()

            return false
        }
    }

    private fun cleanupDeletedFiles(): Int {
        val maxDeletes = CommandLineFlags.maxTranscodeDeletes
        val transcodes = Transcode.findAll().filter { it.file_path != null }

        val missing = transcodes.filter { !Files.exists(Path.of(it.file_path!!)) }

        if (missing.size > maxDeletes) {
            log.warn("Mass deletion guard: {} files missing (max {}), aborting cleanup. " +
                "Possible NAS outage?", missing.size, maxDeletes)
            Broadcaster.broadcastNasScan(NasScanProgress(
                phase = "CLEANUP",
                message = "WARNING: ${missing.size} files missing (max $maxDeletes), cleanup aborted. NAS outage?"
            ))
            return 0
        }

        for (transcode in missing) {
            val episodeId = transcode.episode_id
            transcode.delete()

            // Clean up orphaned episode
            if (episodeId != null) {
                val otherTranscodes = Transcode.findAll().any { it.episode_id == episodeId }
                if (!otherTranscodes) {
                    Episode.findById(episodeId)?.delete()
                }
            }
        }

        return missing.size
    }

    /**
     * Scans for .skip.json files alongside source media files and imports
     * them as SkipSegment rows. These files are produced by external skip
     * detection agents (e.g., MediaSkipDetector).
     *
     * File naming: {video_basename}.{agentname}.skip.json
     * Each file contains a JSON array of {start, end, region_type, ...}.
     */
    private fun importSkipFiles(): Int {
        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val existingSegments = SkipSegment.findAll()
        var imported = 0

        for (tc in transcodes) {
            try {
                val sourceFile = File(tc.file_path!!)
                if (!sourceFile.exists()) continue
                val parentDir = sourceFile.parentFile ?: continue
                val baseName = sourceFile.nameWithoutExtension

                // Find all *.skip.json files matching this source file
                val skipFiles = parentDir.listFiles { _, name ->
                    name.startsWith("$baseName.") && name.endsWith(".skip.json")
                } ?: continue

                for (skipFile in skipFiles) {
                    try {
                        // Extract agent name from filename: baseName.AGENT.skip.json
                        val middlePart = skipFile.name
                            .removePrefix("$baseName.")
                            .removeSuffix(".skip.json")
                        if (middlePart.isEmpty()) continue

                        val agentName = middlePart

                        // Delete existing segments from this agent before re-importing
                        val existingForAgent = existingSegments.filter {
                            it.transcode_id == tc.id && it.detection_method == agentName
                        }

                        val text = skipFile.readText().trim()
                        if (text.isEmpty()) continue

                        val segments = parseSkipJson(text)
                        if (segments.isEmpty()) continue

                        existingForAgent.forEach { it.delete() }

                        // Create new segments
                        for (seg in segments) {
                            SkipSegment(
                                transcode_id = tc.id!!,
                                segment_type = seg.regionType,
                                start_seconds = seg.start,
                                end_seconds = seg.end,
                                detection_method = agentName
                            ).save()
                            imported++
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to parse skip file {}: {}", skipFile.name, e.message)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to scan skip files for {}: {}", tc.file_path, e.message)
            }
        }
        return imported
    }

    private data class SkipEntry(val start: Double, val end: Double, val regionType: String)

    /**
     * Parses a .skip.json file. Expects a JSON array of objects with
     * at minimum "start", "end", and "region_type" fields.
     */
    private fun parseSkipJson(text: String): List<SkipEntry> {
        val results = mutableListOf<SkipEntry>()
        try {
            // Simple regex-based JSON array parser — no external JSON dependency needed
            // Matches each {...} object in the array
            val objectPattern = Regex("""\{[^}]+\}""")
            for (match in objectPattern.findAll(text)) {
                val obj = match.value
                val start = Regex(""""start"\s*:\s*([\d.]+)""").find(obj)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val end = Regex(""""end"\s*:\s*([\d.]+)""").find(obj)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val regionType = Regex(""""region_type"\s*:\s*"(\w+)"""").find(obj)
                    ?.groupValues?.get(1) ?: continue
                if (end > start) {
                    results.add(SkipEntry(start, end, regionType))
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse skip JSON: {}", e.message)
        }
        return results
    }

    /**
     * Updates DB records (transcodes and discovered_files) whose file_path
     * references a file that was just renamed by the sanitizer.
     */
    private fun updateRenamedPaths(renames: List<FilenameSanitizer.RenameResult>) {
        val nasRoot = getNasRootPath() ?: return
        val rootPath = Path.of(nasRoot)
        val sep = rootPath.fileSystem.separator

        // Separate file renames (exact match) from directory renames (prefix match)
        val fileRenames = mutableMapOf<String, String>()
        val dirRenames = mutableListOf<Pair<String, String>>()  // old prefix -> new prefix

        for (rename in renames) {
            val oldAbs = rootPath.resolve(rename.oldPath).toString()
            val newAbs = rootPath.resolve(rename.newPath).toString()
            // If the old path ends with a separator or the new path is a directory, it's a dir rename
            // But we can't check the filesystem (old is gone). Instead, check if any DB path
            // starts with oldAbs + separator — that means it was a directory.
            // Simpler: just always try both exact and prefix matching.
            fileRenames[oldAbs] = newAbs
            dirRenames.add(oldAbs + sep to newAbs + sep)
        }

        var updatedTranscodes = 0
        var updatedDiscovered = 0

        // Update transcodes
        val transcodes = Transcode.findAll()
        for (tc in transcodes) {
            val filePath = tc.file_path ?: continue
            val newPath = fileRenames[filePath]
                ?: dirRenames.firstNotNullOfOrNull { (oldPrefix, newPrefix) ->
                    if (filePath.startsWith(oldPrefix)) filePath.replaceFirst(oldPrefix, newPrefix) else null
                }
            if (newPath != null) {
                log.info("Updating transcode {} path: {} -> {}", tc.id, tc.file_path, newPath)
                tc.file_path = newPath
                tc.save()
                updatedTranscodes++
            }
        }

        // Update discovered_files
        val discoveredFiles = DiscoveredFile.findAll()
        for (df in discoveredFiles) {
            val newPath = fileRenames[df.file_path]
                ?: dirRenames.firstNotNullOfOrNull { (oldPrefix, newPrefix) ->
                    if (df.file_path.startsWith(oldPrefix)) df.file_path.replaceFirst(oldPrefix, newPrefix) else null
                }
            if (newPath != null) {
                log.info("Updating discovered_file {} path: {} -> {}", df.id, df.file_path, newPath)
                df.file_path = newPath
                df.file_name = Path.of(newPath).fileName.toString()
                df.save()
                updatedDiscovered++
            }
        }

        if (updatedTranscodes > 0 || updatedDiscovered > 0) {
            log.info("Updated {} transcode and {} discovered_file DB paths after renames",
                updatedTranscodes, updatedDiscovered)
        }
    }

    /**
     * Copies ForBrowser sprite files (VTT + JPGs) to source directories for all
     * transcodes where ForBrowser sprites exist but source sprites don't.
     */
    private fun promoteSpritesToSource(rootPath: Path): Int {
        val nasRoot = rootPath.toString()
        val transcodes = Transcode.findAll().filter { it.file_path != null }
        var promoted = 0

        for (tc in transcodes) {
            try {
                val sourceFile = File(tc.file_path!!)
                if (!sourceFile.exists()) continue

                val forBrowserMp4 = TranscoderAgent.getForBrowserPath(nasRoot, tc.file_path!!)
                if (!forBrowserMp4.exists()) continue

                val baseName = forBrowserMp4.nameWithoutExtension
                val vttFile = File(forBrowserMp4.parentFile, "$baseName.thumbs.vtt")
                if (!vttFile.exists()) continue

                // Skip if source already has the VTT
                if (sourceFile.parentFile == forBrowserMp4.parentFile) continue
                val sourceVtt = File(sourceFile.parentFile, "$baseName.thumbs.vtt")
                if (sourceVtt.exists() && sourceVtt.lastModified() >= vttFile.lastModified()) continue

                val copied = net.stewart.transcode.ThumbnailSpriteGenerator.copySpritesToDirectory(
                    baseName, forBrowserMp4.parentFile, sourceFile.parentFile
                )
                if (copied > 0) promoted++
            } catch (e: Exception) {
                log.warn("Failed to promote sprites for {}: {}", tc.file_path, e.message)
            }
        }
        return promoted
    }

    private fun getNasRootPath(): String? {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "nas_root_path" }
            ?.config_val
    }

    private fun getPersonalVideoDir(): String? {
        val enabled = AppConfig.findAll()
            .firstOrNull { it.config_key == "personal_video_enabled" }
            ?.config_val
        if (enabled != "true") return null
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "personal_video_nas_dir" }
            ?.config_val
            ?.ifBlank { null }
    }

    private data class DiscoveredFileCandidate(
        val filePath: String,
        val fileName: String,
        val directory: String,
        val fileSizeBytes: Long,
        val mediaType: String,
        val fileModifiedAt: LocalDateTime? = null
    )
}
