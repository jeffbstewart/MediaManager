package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Builds per-title detail JSON for the Roku channel.
 * Used for TV series episode picker and movie detail.
 */
object RokuTitleService {

    private val log = LoggerFactory.getLogger(RokuTitleService::class.java)

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    data class EpisodeItem(
        val episodeId: Long,
        val transcodeId: Long,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String,
        val streamUrl: String,
        val subtitleUrl: String?,
        val bifUrl: String?,
        val quality: String,
        val resumePosition: Int
    )

    data class SeasonGroup(
        val seasonNumber: Int,
        val episodes: List<EpisodeItem>
    )

    data class TitleDetail(
        val titleId: Long,
        val name: String,
        val mediaType: String,
        val year: Int?,
        val description: String?,
        val contentRating: String?,
        val posterUrl: String?,
        val streamUrl: String?,
        val subtitleUrl: String?,
        val bifUrl: String?,
        val quality: String?,
        val transcodeId: Long?,
        val resumePosition: Int,
        val seasons: List<SeasonGroup>?
    )

    fun getTitleDetail(titleId: Long, baseUrl: String, apiKey: String, user: AppUser): TitleDetail? {
        val title = Title.findById(titleId) ?: return null
        if (title.hidden || title.enrichment_status != EnrichmentStatus.ENRICHED.name) return null
        if (!user.canSeeRating(title.content_rating)) return null

        val nasRoot = TranscoderAgent.getNasRoot()
        val transcodes = Transcode.findAll()
            .filter { it.title_id == titleId && it.file_path != null }
            .filter { isPlayable(it, nasRoot) }

        if (transcodes.isEmpty()) return null

        val posterUrl = if (title.poster_path != null) {
            "$baseUrl/posters/w500/${title.id}?key=$apiKey"
        } else null

        // User's progress for this title's transcodes
        val transcodeIds = transcodes.mapNotNull { it.id }.toSet()
        val progressMap: Map<Long?, Int> = PlaybackProgress.findAll()
            .filter { it.user_id == user.id && it.transcode_id in transcodeIds }
            .associate { it.transcode_id to it.position_seconds.toInt() }

        if (title.media_type == MediaType.TV.name) {
            return buildTvDetail(title, transcodes, posterUrl, baseUrl, apiKey, progressMap, nasRoot)
        } else {
            return buildMovieDetail(title, transcodes, posterUrl, baseUrl, apiKey, progressMap, nasRoot)
        }
    }

    private fun buildMovieDetail(
        title: Title,
        transcodes: List<Transcode>,
        posterUrl: String?,
        baseUrl: String,
        apiKey: String,
        progressMap: Map<Long?, Int>,
        nasRoot: String?
    ): TitleDetail {
        val tc = transcodes.first()
        val quality = qualityLabel(tc)
        val resumePos = progressMap[tc.id] ?: 0

        return TitleDetail(
            titleId = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            contentRating = title.content_rating,
            posterUrl = posterUrl,
            streamUrl = "$baseUrl/stream/${tc.id}?key=$apiKey",
            subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null,
            bifUrl = if (hasSpriteSheets(tc, nasRoot)) "$baseUrl/stream/${tc.id}/trickplay.bif?key=$apiKey" else null,
            quality = quality,
            transcodeId = tc.id,
            resumePosition = resumePos,
            seasons = null
        )
    }

    private fun buildTvDetail(
        title: Title,
        transcodes: List<Transcode>,
        posterUrl: String?,
        baseUrl: String,
        apiKey: String,
        progressMap: Map<Long?, Int>,
        nasRoot: String?
    ): TitleDetail {
        val episodes = Episode.findAll().filter { it.title_id == title.id }
        val episodesById = episodes.associateBy { it.id }

        val episodeTranscodes = transcodes.filter { it.episode_id != null }
        if (episodeTranscodes.isEmpty()) return TitleDetail(
            titleId = title.id!!, name = title.name, mediaType = title.media_type,
            year = title.release_year, description = title.description,
            contentRating = title.content_rating, posterUrl = posterUrl,
            streamUrl = null, subtitleUrl = null, bifUrl = null, quality = null,
            transcodeId = null, resumePosition = 0, seasons = emptyList()
        )

        data class EpTc(val episode: Episode, val transcode: Transcode)

        val entries = episodeTranscodes.mapNotNull { tc ->
            val ep = episodesById[tc.episode_id] ?: return@mapNotNull null
            EpTc(ep, tc)
        }

        val bySeason = entries.groupBy { it.episode.season_number }
            .toSortedMap()

        val seasons = bySeason.map { (seasonNum, eps) ->
            val sorted = eps.sortedBy { it.episode.episode_number }
            SeasonGroup(
                seasonNumber = seasonNum,
                episodes = sorted.map { (ep, tc) ->
                    val subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) {
                        "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey"
                    } else null
                    val bifUrl = if (hasSpriteSheets(tc, nasRoot)) {
                        "$baseUrl/stream/${tc.id}/trickplay.bif?key=$apiKey"
                    } else null
                    EpisodeItem(
                        episodeId = ep.id!!,
                        transcodeId = tc.id!!,
                        seasonNumber = seasonNum,
                        episodeNumber = ep.episode_number,
                        name = ep.name ?: "Episode ${ep.episode_number}",
                        streamUrl = "$baseUrl/stream/${tc.id}?key=$apiKey",
                        subtitleUrl = subtitleUrl,
                        bifUrl = bifUrl,
                        quality = qualityLabel(tc),
                        resumePosition = progressMap[tc.id] ?: 0
                    )
                }
            )
        }

        return TitleDetail(
            titleId = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            contentRating = title.content_rating,
            posterUrl = posterUrl,
            streamUrl = null,
            subtitleUrl = null,
            bifUrl = null,
            quality = null,
            transcodeId = null,
            resumePosition = 0,
            seasons = seasons
        )
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

    private fun hasSpriteSheets(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        val mp4File = when {
            ext in DIRECT_EXTENSIONS -> File(filePath)
            ext in TRANSCODE_EXTENSIONS && nasRoot != null -> TranscoderAgent.getForBrowserPath(nasRoot, filePath)
            else -> return false
        }
        if (!mp4File.exists()) return false
        return File(mp4File.parentFile, mp4File.nameWithoutExtension + ".thumbs.vtt").exists()
    }

    private fun hasSubtitleFile(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        val mp4File = when {
            ext in DIRECT_EXTENSIONS -> File(filePath)
            ext in TRANSCODE_EXTENSIONS && nasRoot != null -> TranscoderAgent.getForBrowserPath(nasRoot, filePath)
            else -> return false
        }
        if (!mp4File.exists()) return false
        val srtFile = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".en.srt")
        return srtFile.exists()
    }
}
