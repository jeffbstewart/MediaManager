package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.grpc.isPlayable
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end check that the [Filesystems.current] swap actually wires
 * through `isPlayable` and `TranscoderAgent.isTranscoded` — i.e., the
 * Phase 1 migration unlocks the playable-transcode test paths the
 * Catalog gRPC tests have been working around. No DB setup needed:
 * `isPlayable` only reads `transcode.file_path`, so we construct the
 * row in memory and let JDBI sit idle.
 */
class IsPlayableJimfsTest {

    @get:Rule val fsRule = JimfsRule()

    @Test
    fun `isPlayable is true for an mp4 that exists in Jimfs and false when missing`() {
        // isPlayable only reads transcode.file_path — no DB hit, so we
        // skip the FK-bound save and construct the row in memory.
        val tc = Transcode(title_id = 1, file_path = "/nas/movies/Foundation.mp4")
        // Not seeded yet → not playable.
        assertFalse(isPlayable(tc, nasRoot = "/nas"))
        // Seed → playable.
        fsRule.seed("/nas/movies/Foundation.mp4")
        assertTrue(isPlayable(tc, nasRoot = "/nas"))
    }

    @Test
    fun `isPlayable for an mkv source requires the ForBrowser MP4 to be present`() {
        val tc = Transcode(title_id = 1, file_path = "/nas/movies/Foundation.mkv")
        // Source mkv exists but no ForBrowser → not playable.
        fsRule.seed("/nas/movies/Foundation.mkv")
        assertFalse(isPlayable(tc, nasRoot = "/nas"))
        // Drop in the mirrored ForBrowser MP4 → playable.
        fsRule.seed("/nas/ForBrowser/movies/Foundation.mp4")
        assertTrue(isPlayable(tc, nasRoot = "/nas"))
    }

    @Test
    fun `isPlayable for an unrecognized extension is always false`() {
        val tc = Transcode(title_id = 1, file_path = "/nas/movies/oddball.wmv")
        fsRule.seed("/nas/movies/oddball.wmv")
        assertFalse(isPlayable(tc, nasRoot = "/nas"))
    }

    @Test
    fun `isPlayable returns false when file_path is null`() {
        val tc = Transcode(title_id = 1, file_path = null)
        assertFalse(isPlayable(tc, nasRoot = "/nas"))
    }
}
