package net.stewart.mediamanager.service

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the [NasScannerService.isSameDir] guard correctly identifies the
 * configured music directory even when it sits alongside BLURAY / DVD / UHD
 * siblings under the same NAS root. The NAS scanner's top-level loop uses
 * this to skip classification so audio-only directories don't get walked
 * for video files and mislabelled.
 */
class NasScannerMusicGuardTest {

    @Test
    fun `music sibling of BLURAY is identified by isSameDir`() {
        val tmp = Files.createTempDirectory("mm-nas-guard-")
        try {
            // Set up the typical layout: <nas>/BLURAY, <nas>/DVD, <nas>/MUSIC
            val bluray = Files.createDirectory(tmp.resolve("BLURAY"))
            val dvd = Files.createDirectory(tmp.resolve("DVD"))
            val music = Files.createDirectory(tmp.resolve("MUSIC"))

            val musicRootConfig = music.toAbsolutePath().toString()

            // The scanner's skip guard must identify MUSIC as a match and
            // leave BLURAY / DVD unambiguous.
            assertTrue(NasScannerService.isSameDir(music, musicRootConfig),
                "configured music_root_path should match its own top-level dir")
            assertFalse(NasScannerService.isSameDir(bluray, musicRootConfig),
                "BLURAY must not be mistaken for the music directory")
            assertFalse(NasScannerService.isSameDir(dvd, musicRootConfig),
                "DVD must not be mistaken for the music directory")
        } finally {
            deleteRecursive(tmp)
        }
    }

    @Test
    fun `trailing slash and symlink resolve to the same real path`() {
        val tmp = Files.createTempDirectory("mm-nas-guard-slash-")
        try {
            val music = Files.createDirectory(tmp.resolve("MUSIC"))
            val withSlash = music.toAbsolutePath().toString() + java.io.File.separator
            assertTrue(NasScannerService.isSameDir(music, withSlash),
                "trailing-slash variant should still match via toRealPath()")

            // Symlink only if the filesystem supports it (Windows dev
            // environments often don't without admin) — skip on IOException.
            val linked = tmp.resolve("MUSIC_LINK")
            val canLink = runCatching { Files.createSymbolicLink(linked, music) }.isSuccess
            if (canLink) {
                assertTrue(NasScannerService.isSameDir(linked, music.toAbsolutePath().toString()),
                    "symlink to the music root should match the canonical path")
            }
        } finally {
            deleteRecursive(tmp)
        }
    }

    @Test
    fun `fallback string compare handles missing target gracefully`() {
        val tmp = Files.createTempDirectory("mm-nas-guard-missing-")
        try {
            val music = Files.createDirectory(tmp.resolve("MUSIC"))
            // Point the config at a non-existent path that starts the same way.
            // toRealPath() throws; the fallback string-compare should return false.
            val missing = music.toAbsolutePath().toString() + "_NOPE"
            assertFalse(NasScannerService.isSameDir(music, missing))
        } finally {
            deleteRecursive(tmp)
        }
    }

    private fun deleteRecursive(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }
}
