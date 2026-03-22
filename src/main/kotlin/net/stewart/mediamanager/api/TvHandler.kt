package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscoderAgent
import java.io.File

/**
 * Handles TV-specific endpoints:
 * - GET /catalog/titles/{id}/seasons — list seasons with episode counts
 * - GET /catalog/titles/{id}/seasons/{num}/episodes — episodes with playback state
 */
object TvHandler {

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    fun handleSeasons(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val title = Title.findById(titleId)
        if (title == null || title.hidden || title.media_type != MediaType.TV.name || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val episodes = Episode.findAll().filter { it.title_id == titleId }
        val titleSeasons = TitleSeason.findAll().filter { it.title_id == titleId }.associateBy { it.season_number }

        val seasons = episodes
            .groupBy { it.season_number }
            .toSortedMap()
            .map { (seasonNum, eps) ->
                val titleSeason = titleSeasons[seasonNum]
                ApiSeason(
                    seasonNumber = seasonNum,
                    name = titleSeason?.name,
                    episodeCount = eps.size
                )
            }

        ApiV1Servlet.sendJson(resp, 200, seasons, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    fun handleEpisodes(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long, seasonNumber: Int) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val title = Title.findById(titleId)
        if (title == null || title.hidden || title.media_type != MediaType.TV.name || !user.canSeeRating(title.content_rating)) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val nasRoot = TranscoderAgent.getNasRoot()
        val episodes = Episode.findAll()
            .filter { it.title_id == titleId && it.season_number == seasonNumber }
            .sortedBy { it.episode_number }

        if (episodes.isEmpty()) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val episodeIds = episodes.mapNotNull { it.id }.toSet()
        val transcodes = Transcode.findAll()
            .filter { it.title_id == titleId && it.episode_id in episodeIds && it.file_path != null }
        val transcodesByEpisode = transcodes.groupBy { it.episode_id }

        // Playback progress for this user
        val transcodeIds = transcodes.mapNotNull { it.id }.toSet()
        val progressRecords = PlaybackProgress.findAll()
            .filter { it.user_id == user.id && it.transcode_id in transcodeIds }
        val progressByTranscode = progressRecords.associateBy { it.transcode_id }

        val apiEpisodes = episodes.map { ep ->
            val epTranscodes = transcodesByEpisode[ep.id] ?: emptyList()
            // Pick best transcode (highest format priority)
            val bestTc = epTranscodes
                .filter { isPlayable(it, nasRoot) }
                .maxByOrNull { formatPriority(it) }
            val progress = bestTc?.id?.let { progressByTranscode[it] }

            // For mobile status, check all transcodes for this episode (not just the best playable one)
            val anyTc = epTranscodes.firstOrNull()
            ApiEpisode(
                episodeId = ep.id!!,
                transcodeId = bestTc?.id,
                seasonNumber = ep.season_number,
                episodeNumber = ep.episode_number,
                name = ep.name,
                quality = bestTc?.let { qualityLabel(it) },
                playable = bestTc != null,
                hasSubtitles = bestTc != null && hasSubtitleFile(bestTc, nasRoot),
                resumePosition = progress?.position_seconds ?: 0.0,
                watchedPercent = computeWatchedPercent(progress),
                forMobileAvailable = epTranscodes.any { it.for_mobile_available },
                forMobileRequested = (anyTc?.for_mobile_requested ?: false)
            )
        }

        ApiV1Servlet.sendJson(resp, 200, apiEpisodes, mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun computeWatchedPercent(progress: PlaybackProgress?): Int {
        if (progress == null) return 0
        val duration = progress.duration_seconds ?: return 0
        if (duration <= 0) return 0
        return (progress.position_seconds / duration * 100).toInt().coerceIn(0, 100)
    }

    private fun formatPriority(tc: Transcode): Int = when (tc.media_format) {
        MediaFormat.UHD_BLURAY.name -> 3
        MediaFormat.BLURAY.name -> 2
        MediaFormat.HD_DVD.name -> 1
        else -> 0
    }

    private fun qualityLabel(tc: Transcode): String = when (tc.media_format) {
        MediaFormat.UHD_BLURAY.name -> "UHD"
        MediaFormat.DVD.name -> "SD"
        else -> "FHD"
    }

    private fun isPlayable(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        return when {
            ext in DIRECT_EXTENSIONS -> File(filePath).exists()
            ext in TRANSCODE_EXTENSIONS -> nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
            else -> false
        }
    }

    private fun hasSubtitleFile(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        return TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
    }
}
