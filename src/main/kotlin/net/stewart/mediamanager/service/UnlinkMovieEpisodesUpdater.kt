package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory

/**
 * One-time cleanup: finds MOVIE titles that have multiple transcodes (a sign that
 * TV episode files were incorrectly matched to a movie of the same name), and
 * unlinks ALL transcodes from those movies. Corresponding discovered_file records
 * are deleted so the next NAS scan re-discovers and re-matches them (with the
 * improved media_type–aware matcher).
 *
 * The movie's own legitimate transcode will also be unlinked and re-matched on the
 * next scan — this is safe because the matcher will re-discover and re-link it.
 *
 * This updater is intentionally temporary. Once it has run and the data is clean,
 * it can be removed (or left in place — it's a no-op on subsequent runs because
 * the version is tracked).
 */
class UnlinkMovieEpisodesUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(UnlinkMovieEpisodesUpdater::class.java)

    override val name = "unlink_movie_episodes"
    override val version = 3

    override fun run() {
        val movieTitles = Title.findAll().filter { it.media_type == MediaType.MOVIE.name }
        val allTranscodes = Transcode.findAll()
        val transcodesByTitle = allTranscodes.groupBy { it.title_id }

        // Find movies with more than one transcode — this is the anomaly signal
        val problematicMovies = movieTitles.filter { movie ->
            val transcodes = transcodesByTitle[movie.id] ?: return@filter false
            transcodes.size > 1
        }

        if (problematicMovies.isEmpty()) {
            log.info("No movies with multiple transcodes found — nothing to clean up")
            return
        }

        log.info("Found {} movie(s) with multiple transcodes:", problematicMovies.size)
        for (movie in problematicMovies) {
            val count = transcodesByTitle[movie.id]?.size ?: 0
            log.info("  '{}' (id={}) — {} transcodes", movie.name, movie.id, count)
        }

        val allDiscoveredFiles = DiscoveredFile.findAll()
        var transcodesDeleted = 0
        var episodesDeleted = 0
        var discoveredFilesDeleted = 0

        for (movie in problematicMovies) {
            val transcodes = transcodesByTitle[movie.id] ?: continue

            for (tc in transcodes) {
                val filePath = tc.file_path
                val episodeId = tc.episode_id

                // Delete the corresponding discovered_file so the next NAS scan
                // re-discovers and re-matches it with the fixed matcher
                if (filePath != null) {
                    val df = allDiscoveredFiles.firstOrNull { it.file_path == filePath }
                    if (df != null) {
                        df.delete()
                        discoveredFilesDeleted++
                    }
                }

                // Delete the transcode
                tc.delete()
                transcodesDeleted++

                // Clean up orphaned episode (only if no other transcode references it)
                if (episodeId != null) {
                    val stillReferenced = allTranscodes.any {
                        it.id != tc.id && it.episode_id == episodeId
                    }
                    if (!stillReferenced) {
                        Episode.findById(episodeId)?.delete()
                        episodesDeleted++
                    }
                }
            }

            log.info("Unlinked all transcodes from movie '{}' (id={})", movie.name, movie.id)
        }

        log.info("Cleanup complete: {} transcodes deleted, {} orphaned episodes deleted, " +
            "{} discovered files deleted (will be re-matched on next NAS scan)",
            transcodesDeleted, episodesDeleted, discoveredFilesDeleted)
    }
}
