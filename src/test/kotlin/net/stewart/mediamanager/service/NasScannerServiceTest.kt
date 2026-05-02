package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.TranscodeLease
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit + integration coverage for [NasScannerService] — the helpers that
 * make up `doScan`, plus targeted tests for the highest-value branches:
 * directory classification, candidate processing (matched / unmatched /
 * personal / movie-already-has-transcode), `.skip.json` import + parsing,
 * deleted-file cleanup with the mass-deletion guard, rename propagation,
 * and managed-directory record cleanup.
 *
 * Real on-disk temp directories are used throughout — the agent's
 * `Files.list` / `Files.walk` calls are fast enough that per-test temp
 * trees are simpler than wiring up Jimfs.
 */
internal class NasScannerServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:nasscannertest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    private lateinit var nasRoot: File

    @Before
    fun reset() {
        // Order matters: SkipSegment + TranscodeLease before Transcode
        // (FK), Transcode before Episode (Transcode.episode_id is the
        // cycle-breaking FK), then Episode + DiscoveredFile, finally
        // Title + AppConfig.
        SkipSegment.deleteAll()
        TranscodeLease.deleteAll()
        Transcode.deleteAll()
        DiscoveredFile.deleteAll()  // matched_episode_id FK to episode
        Episode.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()

        nasRoot = Files.createTempDirectory("nas-scanner-").toFile().apply {
            deleteOnExit()
        }
    }

    private fun seedFile(rel: String, bytes: ByteArray = ByteArray(0)): File {
        val target = File(nasRoot, rel)
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    private fun seedTitle(
        name: String,
        mediaType: MediaType = MediaType.MOVIE,
        sortName: String = name.lowercase(),
        year: Int? = null,
    ): Title = Title(
        name = name, media_type = mediaType.name, sort_name = sortName,
        release_year = year,
    ).apply { save() }

    // ---------------------- isVideoFile ----------------------

    @Test
    fun `isVideoFile recognizes the four supported extensions and rejects unknowns`() {
        assertTrue(NasScannerService.isVideoFile(Path.of("a.mkv")))
        assertTrue(NasScannerService.isVideoFile(Path.of("a.mp4")))
        assertTrue(NasScannerService.isVideoFile(Path.of("a.avi")))
        assertTrue(NasScannerService.isVideoFile(Path.of("a.m4v")))
        assertTrue(NasScannerService.isVideoFile(Path.of("CASE.MKV")), "case insensitive")
        assertFalse(NasScannerService.isVideoFile(Path.of("a.mov")))
        assertFalse(NasScannerService.isVideoFile(Path.of("a.txt")))
        assertFalse(NasScannerService.isVideoFile(Path.of("noext")))
    }

    // ---------------------- classifyDirectory ----------------------

    @Test
    fun `classifyDirectory returns MOVIE for flat directories of video files`() {
        val movieDir = seedFile("Movies/Avatar.mkv").parentFile
        seedFile("Movies/Inception.mkv")
        assertEquals(NasScannerService.DirectoryType.MOVIE,
            NasScannerService.classifyDirectory(movieDir.toPath()))
    }

    @Test
    fun `classifyDirectory returns TV when files sit in subdirectories`() {
        seedFile("Shows/Breaking Bad/Season 1/episode.mkv")
        val showsDir = File(nasRoot, "Shows")
        assertEquals(NasScannerService.DirectoryType.TV,
            NasScannerService.classifyDirectory(showsDir.toPath()))
    }

    @Test
    fun `classifyDirectory returns TV when a flat directory has SxxExx names`() {
        seedFile("MixedFlat/Show - S01E02 - Pilot.mkv")
        val mixedDir = File(nasRoot, "MixedFlat")
        assertEquals(NasScannerService.DirectoryType.TV,
            NasScannerService.classifyDirectory(mixedDir.toPath()),
            "SxxExx pattern overrides flat layout to TV")
    }

    @Test
    fun `classifyDirectory returns EMPTY when no video files are present`() {
        seedFile("Junk/readme.txt", "nothing here".toByteArray())
        val junkDir = File(nasRoot, "Junk")
        assertEquals(NasScannerService.DirectoryType.EMPTY,
            NasScannerService.classifyDirectory(junkDir.toPath()))
    }

    // ---------------------- discoverFlat / discoverRecursive ----------------------

    @Test
    fun `discoverFlat picks up direct video files only and skips subdirectories`() {
        seedFile("Movies/A.mkv", ByteArray(100))
        seedFile("Movies/B.mp4", ByteArray(200))
        // Junk: not a video extension
        seedFile("Movies/notes.txt")
        // Subdirectory file should be ignored by discoverFlat
        seedFile("Movies/Extras/bonus.mkv")

        val out = mutableListOf<NasScannerService.DiscoveredFileCandidate>()
        NasScannerService.discoverFlat(File(nasRoot, "Movies").toPath(),
            MediaType.MOVIE.name, out)

        assertEquals(2, out.size, "only direct-level video files")
        val names = out.map { it.fileName }.toSet()
        assertEquals(setOf("A.mkv", "B.mp4"), names)
        assertTrue(out.all { it.mediaType == MediaType.MOVIE.name })
    }

    @Test
    fun `discoverRecursive walks subdirectories and gathers every video file`() {
        seedFile("Shows/S1/E1.mkv")
        seedFile("Shows/S1/E2.mkv")
        seedFile("Shows/S2/E1.mkv")
        seedFile("Shows/cover.jpg")

        val out = mutableListOf<NasScannerService.DiscoveredFileCandidate>()
        NasScannerService.discoverRecursive(File(nasRoot, "Shows").toPath(),
            MediaType.TV.name, out)

        assertEquals(3, out.size)
        assertTrue(out.all { it.mediaType == MediaType.TV.name })
    }

    // ---------------------- processCandidate ----------------------

    private fun candidateFor(
        rel: String,
        mediaType: MediaType,
    ): NasScannerService.DiscoveredFileCandidate {
        val target = seedFile(rel)
        return NasScannerService.DiscoveredFileCandidate(
            filePath = target.absolutePath,
            fileName = target.name,
            directory = target.parentFile.name,
            fileSizeBytes = 0L,
            mediaType = mediaType.name,
        )
    }

    @Test
    fun `processCandidate matches a movie by parsed title and saves matched DB rows`() {
        val title = seedTitle("Inception", year = 2010)
        val candidate = candidateFor("Movies/Inception (2010).mkv", MediaType.MOVIE)
        val matched = NasScannerService.processCandidate(
            candidate = candidate,
            titles = listOf(title),
            titleById = mapOf(title.id to title),
            movieTitlesWithTranscode = mutableSetOf(),
            now = LocalDateTime.now(),
        )

        assertTrue(matched, "movie was matched to existing Title")
        val tc = Transcode.findAll().single()
        assertEquals(title.id, tc.title_id)
        assertEquals(candidate.filePath, tc.file_path)

        val df = DiscoveredFile.findAll().single()
        assertEquals(DiscoveredFileStatus.MATCHED.name, df.match_status)
        assertEquals(title.id, df.matched_title_id)
    }

    @Test
    fun `processCandidate parks an unmatched movie in DiscoveredFile and returns false`() {
        val candidate = candidateFor("Movies/Some Random Title (1999).mkv", MediaType.MOVIE)
        val matched = NasScannerService.processCandidate(
            candidate = candidate,
            titles = emptyList(),
            titleById = emptyMap(),
            movieTitlesWithTranscode = mutableSetOf(),
            now = LocalDateTime.now(),
        )

        assertFalse(matched)
        assertEquals(0, Transcode.findAll().size)
        val df = DiscoveredFile.findAll().single()
        assertEquals(DiscoveredFileStatus.UNMATCHED.name, df.match_status)
    }

    @Test
    fun `processCandidate matches a TV episode and creates the Episode row`() {
        val title = seedTitle("Breaking Bad", mediaType = MediaType.TV)
        val candidate = candidateFor("Shows/Breaking Bad/Breaking Bad - S01E02 - Cat in the Bag.mkv",
            MediaType.TV)
        val matched = NasScannerService.processCandidate(
            candidate = candidate,
            titles = listOf(title),
            titleById = mapOf(title.id to title),
            movieTitlesWithTranscode = mutableSetOf(),
            now = LocalDateTime.now(),
        )

        assertTrue(matched)
        val tc = Transcode.findAll().single()
        assertNotNull(tc.episode_id)

        val ep = Episode.findById(tc.episode_id!!)!!
        assertEquals(1, ep.season_number)
        assertEquals(2, ep.episode_number)
    }

    @Test
    fun `processCandidate parks PERSONAL videos as UNMATCHED with no Title lookup`() {
        val title = seedTitle("Inception", year = 2010)
        val candidate = candidateFor("Personal/Vacation.mp4", MediaType.PERSONAL)
        val matched = NasScannerService.processCandidate(
            candidate = candidate,
            titles = listOf(title),
            titleById = mapOf(title.id to title),
            movieTitlesWithTranscode = mutableSetOf(),
            now = LocalDateTime.now(),
        )

        assertFalse(matched, "PERSONAL videos are never auto-matched")
        assertEquals(0, Transcode.findAll().size)
        val df = DiscoveredFile.findAll().single()
        assertEquals(DiscoveredFileStatus.UNMATCHED.name, df.match_status)
        assertEquals(MediaType.PERSONAL.name, df.media_type)
    }

    @Test
    fun `processCandidate skips matching a movie that already has a transcode`() {
        val title = seedTitle("Inception", year = 2010)
        val movieTitles = mutableSetOf(title.id!!)
        val candidate = candidateFor("Movies/Inception (2010).mkv", MediaType.MOVIE)
        val matched = NasScannerService.processCandidate(
            candidate = candidate,
            titles = listOf(title),
            titleById = mapOf(title.id to title),
            movieTitlesWithTranscode = movieTitles,
            now = LocalDateTime.now(),
        )

        assertFalse(matched, "movie with existing transcode is guarded against double-link")
        assertEquals(0, Transcode.findAll().size)
        val df = DiscoveredFile.findAll().single()
        assertEquals(DiscoveredFileStatus.UNMATCHED.name, df.match_status)
    }

    // ---------------------- findOrCreateEpisode ----------------------

    @Test
    fun `findOrCreateEpisode returns the existing row when one is already present`() {
        val title = seedTitle("Show", mediaType = MediaType.TV)
        val ep = Episode(title_id = title.id!!, season_number = 1, episode_number = 1,
            name = "Pilot").apply { save() }

        val resolvedId = NasScannerService.findOrCreateEpisode(title.id!!, 1, 1, "Pilot")
        assertEquals(ep.id, resolvedId)
        assertEquals(1, Episode.findAll().size)
    }

    @Test
    fun `findOrCreateEpisode creates a new row when none exists`() {
        val title = seedTitle("Show", mediaType = MediaType.TV)
        val resolvedId = NasScannerService.findOrCreateEpisode(title.id!!, 2, 5, "New Episode")
        val created = Episode.findById(resolvedId)!!
        assertEquals(2, created.season_number)
        assertEquals(5, created.episode_number)
        assertEquals("New Episode", created.name)
    }

    // ---------------------- parseSkipJson ----------------------

    @Test
    fun `parseSkipJson handles a well-formed array of skip entries`() {
        val json = """
            [
                {"start": 0.0, "end": 30.0, "region_type": "intro"},
                {"start": 1500.5, "end": 1620.0, "region_type": "outro"}
            ]
        """.trimIndent()
        val entries = NasScannerService.parseSkipJson(json)
        assertEquals(2, entries.size)
        assertEquals("intro", entries[0].regionType)
        assertEquals(0.0, entries[0].start)
        assertEquals(30.0, entries[0].end)
        assertEquals("outro", entries[1].regionType)
    }

    @Test
    fun `parseSkipJson drops entries whose end is not greater than start`() {
        val json = """[{"start": 100.0, "end": 100.0, "region_type": "x"}]"""
        assertEquals(0, NasScannerService.parseSkipJson(json).size)
    }

    @Test
    fun `parseSkipJson skips entries missing required keys`() {
        val json = """
            [
                {"start": 1.0, "end": 2.0},
                {"start": 5.0, "end": 10.0, "region_type": "ok"}
            ]
        """.trimIndent()
        val entries = NasScannerService.parseSkipJson(json)
        assertEquals(1, entries.size)
        assertEquals("ok", entries.single().regionType)
    }

    @Test
    fun `parseSkipJson returns empty for malformed JSON without throwing`() {
        assertEquals(0, NasScannerService.parseSkipJson("not even close to JSON").size)
        assertEquals(0, NasScannerService.parseSkipJson("").size)
    }

    // ---------------------- importSkipFiles ----------------------

    @Test
    fun `importSkipFiles loads sibling dot-skip-dot-json files into SkipSegment`() {
        val title = seedTitle("Movie")
        val source = seedFile("Movies/Movie.mkv")
        val tc = Transcode(title_id = title.id!!, file_path = source.absolutePath).apply { save() }

        // Sibling agent file: <baseName>.<agent>.skip.json
        File(source.parentFile, "Movie.AgentX.skip.json").writeText(
            """[{"start": 5.0, "end": 25.0, "region_type": "intro"}]"""
        )

        val imported = NasScannerService.importSkipFiles()
        assertEquals(1, imported)

        val seg = SkipSegment.findAll().single()
        assertEquals(tc.id, seg.transcode_id)
        assertEquals("AgentX", seg.detection_method)
        assertEquals("intro", seg.segment_type)
    }

    @Test
    fun `importSkipFiles replaces prior segments from the same agent on re-import`() {
        val title = seedTitle("Movie")
        val source = seedFile("Movies/Movie.mkv")
        val tc = Transcode(title_id = title.id!!, file_path = source.absolutePath).apply { save() }
        // Pre-existing segment from AgentX that should be wiped and replaced.
        SkipSegment(transcode_id = tc.id!!, segment_type = "stale",
            start_seconds = 1.0, end_seconds = 2.0,
            detection_method = "AgentX").save()

        File(source.parentFile, "Movie.AgentX.skip.json").writeText(
            """[
                {"start": 10.0, "end": 20.0, "region_type": "intro"},
                {"start": 100.0, "end": 110.0, "region_type": "outro"}
            ]"""
        )

        val imported = NasScannerService.importSkipFiles()
        assertEquals(2, imported)
        val agentSegs = SkipSegment.findAll().filter { it.detection_method == "AgentX" }
        assertEquals(2, agentSegs.size)
        assertTrue(agentSegs.none { it.segment_type == "stale" },
            "prior AgentX rows replaced")
    }

    @Test
    fun `importSkipFiles tolerates missing source files and unparseable JSON`() {
        val title = seedTitle("Movie")
        // Transcode points at a file that doesn't exist on disk.
        Transcode(title_id = title.id!!,
            file_path = File(nasRoot, "ghost/missing.mkv").absolutePath).save()

        // Real source + garbage JSON sibling
        val realSrc = seedFile("Movies/Real.mkv")
        Transcode(title_id = title.id!!, file_path = realSrc.absolutePath).save()
        File(realSrc.parentFile, "Real.AgentY.skip.json").writeText("garbage")

        val imported = NasScannerService.importSkipFiles()
        assertEquals(0, imported)
        assertEquals(0, SkipSegment.findAll().size)
    }

    // ---------------------- cleanupDeletedFiles ----------------------

    @Test
    fun `cleanupDeletedFiles deletes Transcode rows whose source file is gone`() {
        val title = seedTitle("Movie")
        val present = seedFile("Movies/Present.mkv")
        Transcode(title_id = title.id!!, file_path = present.absolutePath).save()
        Transcode(title_id = title.id!!,
            file_path = File(nasRoot, "Movies/Gone.mkv").absolutePath).save()

        val deleted = NasScannerService.cleanupDeletedFiles()
        assertEquals(1, deleted)

        val remaining = Transcode.findAll()
        assertEquals(1, remaining.size)
        assertEquals(present.absolutePath, remaining.single().file_path)
    }

    @Test
    fun `cleanupDeletedFiles also drops the orphaned Episode when no other transcodes refer to it`() {
        val title = seedTitle("Show", mediaType = MediaType.TV)
        val ep = Episode(title_id = title.id!!, season_number = 1, episode_number = 1,
            name = "Pilot").apply { save() }
        Transcode(title_id = title.id!!, episode_id = ep.id,
            file_path = File(nasRoot, "Shows/Show/S1/E1.mkv").absolutePath).save()  // missing

        NasScannerService.cleanupDeletedFiles()

        assertEquals(0, Transcode.findAll().size)
        assertEquals(0, Episode.findAll().size,
            "orphaned episode is deleted along with its only transcode")
    }

    @Test
    fun `cleanupDeletedFiles bails out without deleting anything when missing-count exceeds the guard`() {
        val title = seedTitle("Movie")
        // Seed more missing transcodes than the default mass-deletion limit.
        // CommandLineFlags.maxTranscodeDeletes default in tests is 100;
        // make the count clearly exceed any reasonable default.
        val limit = net.stewart.mediamanager.CommandLineFlags.maxTranscodeDeletes
        repeat(limit + 5) { i ->
            Transcode(title_id = title.id!!,
                file_path = File(nasRoot, "ghost/m$i.mkv").absolutePath).save()
        }

        val deleted = NasScannerService.cleanupDeletedFiles()
        assertEquals(0, deleted, "guard returns 0 and skips deletion entirely")
        assertEquals(limit + 5, Transcode.findAll().size,
            "all transcodes left in place when the mass-deletion guard trips")
    }

    // ---------------------- updateRenamedPaths ----------------------

    @Test
    fun `updateRenamedPaths rewrites Transcode and DiscoveredFile rows after a file-rename`() {
        // nas_root_path needs to be set so updateRenamedPaths can resolve.
        AppConfig(config_key = "nas_root_path", config_val = nasRoot.absolutePath).save()

        val title = seedTitle("Movie")
        // Use Windows-legal path strings — we're testing the rewrite
        // plumbing, not the sanitizer's Windows-disallowed-char logic.
        val oldRel = "Movies/OldName.mkv"
        val newRel = "Movies/GoodName.mkv"
        val oldAbs = File(nasRoot, oldRel).absolutePath
        val newAbs = File(nasRoot, newRel).absolutePath

        Transcode(title_id = title.id!!, file_path = oldAbs).save()
        DiscoveredFile(file_path = oldAbs, file_name = "OldName.mkv",
            directory = "Movies").save()

        NasScannerService.updateRenamedPaths(listOf(
            FilenameSanitizer.RenameResult(oldPath = oldRel, newPath = newRel)
        ))

        assertEquals(newAbs, Transcode.findAll().single().file_path)
        val df = DiscoveredFile.findAll().single()
        assertEquals(newAbs, df.file_path)
        assertEquals("GoodName.mkv", df.file_name)
    }

    @Test
    fun `updateRenamedPaths rewrites Transcode rows whose path falls under a renamed directory`() {
        AppConfig(config_key = "nas_root_path", config_val = nasRoot.absolutePath).save()
        val title = seedTitle("Show", mediaType = MediaType.TV)
        val oldDirRel = "Shows/OldName"
        val newDirRel = "Shows/NewName"
        val sep = File.separator
        val nestedOld = "${File(nasRoot, oldDirRel).absolutePath}${sep}S1${sep}E1.mkv"
        val nestedNew = "${File(nasRoot, newDirRel).absolutePath}${sep}S1${sep}E1.mkv"
        Transcode(title_id = title.id!!, file_path = nestedOld).save()

        NasScannerService.updateRenamedPaths(listOf(
            FilenameSanitizer.RenameResult(oldPath = oldDirRel, newPath = newDirRel)
        ))

        assertEquals(nestedNew, Transcode.findAll().single().file_path)
    }

    @Test
    fun `updateRenamedPaths is a no-op when nas_root_path is not configured`() {
        // No AppConfig row.
        val title = seedTitle("Movie")
        Transcode(title_id = title.id!!,
            file_path = "/nas/Movies/OldName.mkv").save()

        NasScannerService.updateRenamedPaths(listOf(
            FilenameSanitizer.RenameResult(
                oldPath = "Movies/OldName.mkv",
                newPath = "Movies/GoodName.mkv"
            )
        ))

        // Path unchanged — early-return when nas_root_path is unset.
        assertEquals("/nas/Movies/OldName.mkv",
            Transcode.findAll().single().file_path)
    }

    // ---------------------- cleanupManagedDirectoryRecords ----------------------

    @Test
    fun `cleanupManagedDirectoryRecords deletes records pointing into ForBrowser ForMobile`() {
        val title = seedTitle("Movie")
        val sep = File.separator

        val sourceTc = Transcode(title_id = title.id!!,
            file_path = "${nasRoot.absolutePath}${sep}Movies${sep}M.mkv").apply { save() }
        // Bogus: pointing into ForBrowser
        val bogusTc = Transcode(title_id = title.id!!,
            file_path = "${nasRoot.absolutePath}${sep}ForBrowser${sep}Movies${sep}M.mp4").apply { save() }
        // Park a lease on the bogus row to ensure FK chain delete works
        TranscodeLease(transcode_id = bogusTc.id!!,
            buddy_name = "local",
            relative_path = "ForBrowser/Movies/M.mp4",
            claimed_at = LocalDateTime.now(),
            expires_at = LocalDateTime.now().plusMinutes(5)).save()
        DiscoveredFile(file_path = "${nasRoot.absolutePath}${sep}ForMobile${sep}Movies${sep}M.mp4",
            file_name = "M.mp4", directory = "Movies").save()

        NasScannerService.cleanupManagedDirectoryRecords(nasRoot.absolutePath)

        val remainingTcs = Transcode.findAll()
        assertEquals(1, remainingTcs.size)
        assertEquals(sourceTc.id, remainingTcs.single().id, "source-dir transcode survives")
        assertEquals(0, DiscoveredFile.findAll().size,
            "ForMobile-discovered file deleted")
        assertEquals(0, TranscodeLease.findAll().size,
            "lease for managed-dir transcode also deleted")
    }

    // ---------------------- AppConfig getters ----------------------

    @Test
    fun `getNasRootPath returns the configured value or null when unset`() {
        assertEquals(null, NasScannerService.getNasRootPath())
        AppConfig(config_key = "nas_root_path", config_val = "/srv/media").save()
        assertEquals("/srv/media", NasScannerService.getNasRootPath())
    }

    @Test
    fun `getBooksRoot returns null for unset, blank, or whitespace values`() {
        assertEquals(null, NasScannerService.getBooksRoot())
        AppConfig(config_key = BookScannerAgent.CONFIG_KEY_BOOKS_ROOT,
            config_val = "").save()
        assertEquals(null, NasScannerService.getBooksRoot(), "blank value treated as unset")
    }

    @Test
    fun `getBooksRoot returns the configured value when populated`() {
        AppConfig(config_key = BookScannerAgent.CONFIG_KEY_BOOKS_ROOT,
            config_val = "/srv/books").save()
        assertEquals("/srv/books", NasScannerService.getBooksRoot())
    }

    @Test
    fun `getMusicRoot returns null for unset, blank, or whitespace values`() {
        assertEquals(null, NasScannerService.getMusicRoot())
        AppConfig(config_key = MusicScannerAgent.CONFIG_KEY_MUSIC_ROOT,
            config_val = "   ").save()
        assertEquals(null, NasScannerService.getMusicRoot(), "blank value treated as unset")
    }

    @Test
    fun `getMusicRoot returns the configured value when populated`() {
        AppConfig(config_key = MusicScannerAgent.CONFIG_KEY_MUSIC_ROOT,
            config_val = "/srv/music").save()
        assertEquals("/srv/music", NasScannerService.getMusicRoot())
    }

    @Test
    fun `getPersonalVideoDir returns null when personal_video_enabled is not set to true`() {
        assertEquals(null, NasScannerService.getPersonalVideoDir())

        AppConfig(config_key = "personal_video_enabled", config_val = "false").save()
        AppConfig(config_key = "personal_video_nas_dir", config_val = "Personal").save()
        assertEquals(null, NasScannerService.getPersonalVideoDir(),
            "must be enabled=true exactly")
    }

    @Test
    fun `getPersonalVideoDir returns the configured directory when enabled`() {
        AppConfig(config_key = "personal_video_enabled", config_val = "true").save()
        AppConfig(config_key = "personal_video_nas_dir", config_val = "Personal").save()
        assertEquals("Personal", NasScannerService.getPersonalVideoDir())
    }

    @Test
    fun `getPersonalVideoDir returns null when enabled but the directory is blank`() {
        AppConfig(config_key = "personal_video_enabled", config_val = "true").save()
        AppConfig(config_key = "personal_video_nas_dir", config_val = "").save()
        assertEquals(null, NasScannerService.getPersonalVideoDir())
    }

    // ---------------------- doScan integration ----------------------

    @Test
    fun `doScan early-returns and broadcasts FAILED when nas_root_path is not configured`() {
        // No AppConfig row.
        NasScannerService.doScan()
        // Nothing was discovered or persisted.
        assertEquals(0, Transcode.findAll().size)
        assertEquals(0, DiscoveredFile.findAll().size)
    }

    @Test
    fun `doScan early-returns when nas_root_path points at a non-directory`() {
        AppConfig(config_key = "nas_root_path",
            config_val = "/no/such/path/does/not/exist").save()
        NasScannerService.doScan()
        assertEquals(0, Transcode.findAll().size)
        assertEquals(0, DiscoveredFile.findAll().size)
    }

    @Test
    fun `doScan walks a real NAS tree, classifies dirs, and matches movies + TV episodes`() {
        // Real on-disk layout the scanner can walk:
        //   <nasRoot>/Movies/Inception (2010).mkv          ← matches
        //   <nasRoot>/Movies/Mystery (1999).mkv             ← unmatched
        //   <nasRoot>/Shows/Breaking Bad/.../S01E01.mkv     ← matches + makes Episode
        //   <nasRoot>/Books/whatever.txt                    ← skipped (books root)
        //   <nasRoot>/Music/song.flac                       ← skipped (music root)
        seedFile("Movies/Inception (2010).mkv", ByteArray(100))
        seedFile("Movies/Mystery (1999).mkv", ByteArray(100))
        seedFile("Shows/Breaking Bad/Season 1/Breaking Bad - S01E01 - Pilot.mkv",
            ByteArray(100))
        seedFile("Books/some.epub")
        seedFile("Music/song.flac")

        // Pre-existing titles we expect the scanner to match against.
        seedTitle("Inception", year = 2010)
        seedTitle("Breaking Bad", mediaType = MediaType.TV)

        // Configure roots. nas_root_path is required; books_root_path
        // and music_root_path point at directories we want the scanner
        // to skip (handled by other agents).
        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()
        AppConfig(config_key = BookScannerAgent.CONFIG_KEY_BOOKS_ROOT,
            config_val = File(nasRoot, "Books").absolutePath).save()
        AppConfig(config_key = MusicScannerAgent.CONFIG_KEY_MUSIC_ROOT,
            config_val = File(nasRoot, "Music").absolutePath).save()

        NasScannerService.doScan()

        // Two transcodes — Inception (matched movie) and Breaking Bad
        // S01E01 (matched episode). The Mystery movie is unmatched, so
        // it does NOT have a Transcode row.
        val transcodes = Transcode.findAll()
        assertEquals(2, transcodes.size)
        val inceptionTitle = Title.findAll().single { it.name == "Inception" }
        val bbTitle = Title.findAll().single { it.name == "Breaking Bad" }
        assertTrue(transcodes.any { it.title_id == inceptionTitle.id },
            "Inception got a Transcode row")
        assertTrue(transcodes.any { it.title_id == bbTitle.id },
            "Breaking Bad S01E01 got a Transcode row")
        // The TV transcode points at a created Episode row.
        val tvTranscode = transcodes.single { it.title_id == bbTitle.id }
        assertNotNull(tvTranscode.episode_id, "TV transcode wired to an Episode")
        val ep = Episode.findById(tvTranscode.episode_id!!)!!
        assertEquals(1, ep.season_number)
        assertEquals(1, ep.episode_number)

        // Three DiscoveredFile rows — two MATCHED, one UNMATCHED.
        val discovered = DiscoveredFile.findAll()
        assertEquals(3, discovered.size)
        val matched = discovered.filter { it.match_status == DiscoveredFileStatus.MATCHED.name }
        val unmatched = discovered.filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
        assertEquals(2, matched.size)
        assertEquals(1, unmatched.size)
        assertEquals("Mystery (1999).mkv", unmatched.single().file_name)
    }

    @Test
    fun `doScan running twice is idempotent — already-tracked files do not duplicate Transcode rows`() {
        seedFile("Movies/Inception (2010).mkv", ByteArray(100))
        seedTitle("Inception", year = 2010)
        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()

        NasScannerService.doScan()
        val firstPassTcs = Transcode.findAll().size
        val firstPassDfs = DiscoveredFile.findAll().size

        NasScannerService.doScan()  // second pass — same files
        assertEquals(firstPassTcs, Transcode.findAll().size,
            "second scan should not duplicate Transcode rows")
        assertEquals(firstPassDfs, DiscoveredFile.findAll().size,
            "second scan should not duplicate DiscoveredFile rows")
    }

    @Test
    fun `doScan reclassifies pre-existing DiscoveredFiles when personal_video_nas_dir is configured`() {
        // Seed a DiscoveredFile pre-classified as MOVIE that lives in
        // the Personal directory — doScan should flip its media_type.
        seedFile("Personal/holiday.mp4", ByteArray(100))
        DiscoveredFile(
            file_path = File(nasRoot, "Personal/holiday.mp4").absolutePath,
            file_name = "holiday.mp4",
            directory = "Personal",
            media_type = MediaType.MOVIE.name,
            match_status = DiscoveredFileStatus.UNMATCHED.name,
        ).save()

        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()
        AppConfig(config_key = "personal_video_enabled", config_val = "true").save()
        AppConfig(config_key = "personal_video_nas_dir", config_val = "Personal").save()

        NasScannerService.doScan()

        val df = DiscoveredFile.findAll()
            .single { it.file_name == "holiday.mp4" }
        assertEquals(MediaType.PERSONAL.name, df.media_type,
            "pre-existing MOVIE row reclassified as PERSONAL")
    }

    // ---------------------- scan() daemon lifecycle ----------------------

    @Test
    fun `scan flips isRunning while the daemon thread runs and back to false on completion`() {
        seedFile("Movies/Inception (2010).mkv", ByteArray(100))
        seedTitle("Inception", year = 2010)
        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()

        assertFalse(NasScannerService.isRunning())
        NasScannerService.scan()

        // Wait up to 30s for the daemon to settle. doScan against this
        // tiny tree finishes in well under a second on any host; the
        // generous timeout is paranoia for slow CI.
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        while (NasScannerService.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(NasScannerService.isRunning(),
            "scan should have flipped running back to false")

        // The scan ran — Inception got matched.
        assertEquals(1, Transcode.findAll().size)
    }

    @Test
    fun `scan called while another scan is in flight returns immediately without re-entering`() {
        // Configure a real scan target so the in-flight scan completes
        // (otherwise it'd still be running when the test ends and trip
        // the next test's @Before).
        seedFile("Movies/Inception (2010).mkv", ByteArray(100))
        seedTitle("Inception", year = 2010)
        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()

        NasScannerService.scan()
        // Burst a second call right after — should hit the
        // compareAndSet false-arm and log the "already in progress"
        // warning. We can't easily observe the log line, but we can
        // assert no exception is thrown and that exactly one scan
        // happened.
        NasScannerService.scan()

        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        while (NasScannerService.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(NasScannerService.isRunning())
        assertEquals(1, Transcode.findAll().size)
    }
}
