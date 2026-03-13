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

    data class TagItem(
        val id: Long,
        val name: String
    )

    data class CastItem(
        val castMemberId: Long,
        val tmdbPersonId: Int,
        val name: String,
        val character: String?,
        val headshotUrl: String?
    )

    data class SimilarItem(
        val titleId: Long,
        val name: String,
        val posterUrl: String?,
        val year: Int?,
        val mediaType: String,
        val quality: String?,
        val contentRating: String?
    )

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
        val resumePosition: Int,
        val watchedPercent: Int
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
        val backdropUrl: String?,
        val tags: List<TagItem>,
        val streamUrl: String?,
        val subtitleUrl: String?,
        val bifUrl: String?,
        val quality: String?,
        val transcodeId: Long?,
        val resumePosition: Int,
        val watchedPercent: Int,
        val seasons: List<SeasonGroup>?,
        val nextSeasonIndex: Int?,
        val nextEpisodeIndex: Int?,
        val cast: List<CastItem>,
        val similarTitles: List<SimilarItem>
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

        val backdropUrl = if (title.backdrop_path != null) {
            "$baseUrl/backdrops/${title.id}?key=$apiKey"
        } else null

        // Tags for this title
        val tagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        val tags = if (tagIds.isNotEmpty()) {
            Tag.findAll().filter { it.id in tagIds }.map { TagItem(id = it.id!!, name = it.name) }
        } else emptyList()

        // Full progress records for next-up and watchedPercent
        val transcodeIds = transcodes.mapNotNull { it.id }.toSet()
        val progressRecords = PlaybackProgress.findAll()
            .filter { it.user_id == user.id && it.transcode_id in transcodeIds }
        val progressMap: Map<Long?, Int> = progressRecords.associate { it.transcode_id to it.position_seconds.toInt() }

        // Cast (top 10 by credit order)
        val cast = buildCast(titleId, baseUrl, apiKey)

        // Similar titles (genre + cast overlap, no TMDB API call)
        val similarTitles = buildSimilarTitles(title, baseUrl, apiKey, user, nasRoot)

        if (title.media_type == MediaType.TV.name) {
            return buildTvDetail(title, transcodes, posterUrl, backdropUrl, tags, baseUrl, apiKey, progressMap, progressRecords, nasRoot, cast, similarTitles)
        } else {
            return buildMovieDetail(title, transcodes, posterUrl, backdropUrl, tags, baseUrl, apiKey, progressMap, progressRecords, nasRoot, cast, similarTitles)
        }
    }

    private fun buildMovieDetail(
        title: Title,
        transcodes: List<Transcode>,
        posterUrl: String?,
        backdropUrl: String?,
        tags: List<TagItem>,
        baseUrl: String,
        apiKey: String,
        progressMap: Map<Long?, Int>,
        progressRecords: List<PlaybackProgress>,
        nasRoot: String?,
        cast: List<CastItem>,
        similarTitles: List<SimilarItem>
    ): TitleDetail {
        val tc = transcodes.first()
        val quality = qualityLabel(tc)
        val resumePos = progressMap[tc.id] ?: 0

        val progress = progressRecords.firstOrNull { it.transcode_id == tc.id }
        val watchedPct = computeWatchedPercent(progress)

        return TitleDetail(
            titleId = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            contentRating = title.content_rating,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            tags = tags,
            streamUrl = "$baseUrl/stream/${tc.id}?key=$apiKey",
            subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey" else null,
            bifUrl = if (hasSpriteSheets(tc, nasRoot)) "$baseUrl/stream/${tc.id}/trickplay.bif?key=$apiKey" else null,
            quality = quality,
            transcodeId = tc.id,
            resumePosition = resumePos,
            watchedPercent = watchedPct,
            seasons = null,
            nextSeasonIndex = null,
            nextEpisodeIndex = null,
            cast = cast,
            similarTitles = similarTitles
        )
    }

    private fun buildTvDetail(
        title: Title,
        transcodes: List<Transcode>,
        posterUrl: String?,
        backdropUrl: String?,
        tags: List<TagItem>,
        baseUrl: String,
        apiKey: String,
        progressMap: Map<Long?, Int>,
        progressRecords: List<PlaybackProgress>,
        nasRoot: String?,
        cast: List<CastItem>,
        similarTitles: List<SimilarItem>
    ): TitleDetail {
        val episodes = Episode.findAll().filter { it.title_id == title.id }
        val episodesById = episodes.associateBy { it.id }

        val episodeTranscodes = transcodes.filter { it.episode_id != null }
        if (episodeTranscodes.isEmpty()) return TitleDetail(
            titleId = title.id!!, name = title.name, mediaType = title.media_type,
            year = title.release_year, description = title.description,
            contentRating = title.content_rating, posterUrl = posterUrl,
            backdropUrl = backdropUrl, tags = tags,
            streamUrl = null, subtitleUrl = null, bifUrl = null, quality = null,
            transcodeId = null, resumePosition = 0, watchedPercent = 0,
            seasons = emptyList(),
            nextSeasonIndex = 0, nextEpisodeIndex = 0,
            cast = cast, similarTitles = similarTitles
        )

        data class EpTc(val episode: Episode, val transcode: Transcode)

        // Deduplicate: keep best transcode per episode (prefer higher quality format)
        val entries = episodeTranscodes.mapNotNull { tc ->
            val ep = episodesById[tc.episode_id] ?: return@mapNotNull null
            EpTc(ep, tc)
        }.groupBy { it.episode.id }
            .mapNotNull { (_, group) ->
                group.maxByOrNull { formatPriority(it.transcode) }
            }

        val bySeason = entries.groupBy { it.episode.season_number }
            .toSortedMap()

        // Build transcode → (seasonIndex, episodeIndex) mapping for next-up computation
        val transcodeToIndex = mutableMapOf<Long, Pair<Int, Int>>()
        var seasonIdx = 0

        val seasons = bySeason.map { (seasonNum, eps) ->
            val sorted = eps.sortedBy { it.episode.episode_number }
            val seasonGroup = SeasonGroup(
                seasonNumber = seasonNum,
                episodes = sorted.mapIndexed { epIdx, (ep, tc) ->
                    if (tc.id != null) {
                        transcodeToIndex[tc.id!!] = seasonIdx to epIdx
                    }
                    val subtitleUrl = if (hasSubtitleFile(tc, nasRoot)) {
                        "$baseUrl/stream/${tc.id}/subs.srt?key=$apiKey"
                    } else null
                    val bifUrl = if (hasSpriteSheets(tc, nasRoot)) {
                        "$baseUrl/stream/${tc.id}/trickplay.bif?key=$apiKey"
                    } else null
                    val progress = progressRecords.firstOrNull { it.transcode_id == tc.id }
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
                        resumePosition = progressMap[tc.id] ?: 0,
                        watchedPercent = computeWatchedPercent(progress)
                    )
                }
            )
            seasonIdx++
            seasonGroup
        }

        // Compute next-up episode using rewatch-aware algorithm
        val (nextSeason, nextEpisode) = computeNextUp(seasons, progressRecords, transcodeToIndex)

        return TitleDetail(
            titleId = title.id!!,
            name = title.name,
            mediaType = title.media_type,
            year = title.release_year,
            description = title.description,
            contentRating = title.content_rating,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            tags = tags,
            streamUrl = null,
            subtitleUrl = null,
            bifUrl = null,
            quality = null,
            transcodeId = null,
            resumePosition = 0,
            watchedPercent = 0,
            seasons = seasons,
            nextSeasonIndex = nextSeason,
            nextEpisodeIndex = nextEpisode,
            cast = cast,
            similarTitles = similarTitles
        )
    }

    // ---- Next-Up Algorithm (rewatch-aware) ----

    /**
     * Finds the next episode to watch based on playback history.
     *
     * Algorithm: find the most recently touched episode (by updated_at).
     * If it's >=90% watched, advance to the next episode.
     * If it's in-progress (<90%), resume it.
     * If no history exists, start at S01E01.
     *
     * This naturally handles rewatches: if a user rewatches E1 then E2,
     * the most recent is E2, so next-up is E3 — even if E3 was watched months ago.
     */
    private fun computeNextUp(
        seasons: List<SeasonGroup>,
        progressRecords: List<PlaybackProgress>,
        transcodeToIndex: Map<Long, Pair<Int, Int>>
    ): Pair<Int, Int> {
        if (seasons.isEmpty()) return 0 to 0

        // Find the most recently updated progress record that maps to a known episode
        val mostRecent = progressRecords
            .filter { it.updated_at != null && it.transcode_id in transcodeToIndex }
            .maxByOrNull { it.updated_at!! }
            ?: return 0 to 0  // No progress → first episode

        val (seasonIdx, epIdx) = transcodeToIndex[mostRecent.transcode_id] ?: return 0 to 0
        val watchedPct = computeWatchedPercent(mostRecent)

        if (watchedPct >= 90) {
            // Finished → advance to next episode
            val season = seasons.getOrNull(seasonIdx) ?: return 0 to 0
            if (epIdx + 1 < season.episodes.size) {
                return seasonIdx to (epIdx + 1)
            }
            if (seasonIdx + 1 < seasons.size) {
                return (seasonIdx + 1) to 0
            }
            // Last episode of last season → wrap to beginning for fresh rewatch
            return 0 to 0
        } else {
            // In progress → resume this episode
            return seasonIdx to epIdx
        }
    }

    private fun computeWatchedPercent(progress: PlaybackProgress?): Int {
        if (progress == null) return 0
        val duration = progress.duration_seconds ?: return 0
        if (duration <= 0) return 0
        return (progress.position_seconds / duration * 100).toInt().coerceIn(0, 100)
    }

    // ---- Cast ----

    private fun buildCast(titleId: Long, baseUrl: String, apiKey: String): List<CastItem> {
        return CastMember.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.cast_order }
            .take(10)
            .map { cm ->
                CastItem(
                    castMemberId = cm.id!!,
                    tmdbPersonId = cm.tmdb_person_id,
                    name = cm.name,
                    character = cm.character_name,
                    headshotUrl = if (cm.profile_path != null) "$baseUrl/headshots/${cm.id}?key=$apiKey" else null
                )
            }
    }

    // ---- Similar Titles (genre + cast overlap, no TMDB API call) ----

    private fun buildSimilarTitles(
        title: Title,
        baseUrl: String,
        apiKey: String,
        user: AppUser,
        nasRoot: String?
    ): List<SimilarItem> {
        val titleId = title.id ?: return emptyList()

        val allTitles = Title.findAll().filter {
            it.id != titleId && !it.hidden &&
                it.enrichment_status == EnrichmentStatus.ENRICHED.name &&
                user.canSeeRating(it.content_rating)
        }
        if (allTitles.isEmpty()) return emptyList()

        val titleById = allTitles.associateBy { it.id }
        val scores = mutableMapOf<Long, Int>()

        // Genre overlap (+2 per shared genre)
        val myGenreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
        if (myGenreIds.isNotEmpty()) {
            val genresByTitle = TitleGenre.findAll().groupBy { it.title_id }
            for ((candidateId, tgs) in genresByTitle) {
                if (candidateId == titleId || candidateId !in titleById) continue
                val shared = tgs.count { it.genre_id in myGenreIds }
                if (shared > 0) {
                    scores[candidateId] = (scores[candidateId] ?: 0) + (shared * 2)
                }
            }
        }

        // Cast overlap (+3 per shared top-5 cast member)
        val myCast = CastMember.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.cast_order }
            .take(5)
            .map { it.tmdb_person_id }
            .toSet()
        if (myCast.isNotEmpty()) {
            val castByTitle = CastMember.findAll().groupBy { it.title_id }
            for ((candidateId, members) in castByTitle) {
                if (candidateId == titleId || candidateId !in titleById) continue
                val shared = members.count { it.tmdb_person_id in myCast }
                if (shared > 0) {
                    scores[candidateId] = (scores[candidateId] ?: 0) + (shared * 3)
                }
            }
        }

        // Filter to playable titles and take top 8
        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        return scores.entries
            .filter { titleById[it.key] != null && playableByTitle[it.key]?.isNotEmpty() == true }
            .sortedByDescending { it.value }
            .take(8)
            .mapNotNull { entry ->
                val t = titleById[entry.key] ?: return@mapNotNull null
                val tc = playableByTitle[t.id]?.firstOrNull()
                SimilarItem(
                    titleId = t.id!!,
                    name = t.name,
                    posterUrl = if (t.poster_path != null) "$baseUrl/posters/w500/${t.id}?key=$apiKey" else null,
                    year = t.release_year,
                    mediaType = t.media_type,
                    quality = if (tc != null) qualityLabel(tc) else null,
                    contentRating = t.content_rating
                )
            }
    }

    // ---- Helpers ----

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
