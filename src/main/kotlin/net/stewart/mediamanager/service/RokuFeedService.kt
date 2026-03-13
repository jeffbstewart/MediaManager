package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds a Roku Direct Publisher JSON feed from the media catalog.
 *
 * Only includes enriched, non-hidden titles that have at least one playable transcode
 * (MP4/M4V direct, or MKV/AVI with a ForBrowser transcoded copy on disk).
 */
object RokuFeedService {

    private val log = LoggerFactory.getLogger(RokuFeedService::class.java)

    private val mapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    /**
     * Generates the Roku Direct Publisher JSON feed.
     *
     * @param baseUrl scheme + host + port of the server (e.g. "https://myserver.example.com")
     * @param apiKey the Roku API key to embed in poster/stream URLs for authentication
     * @return JSON string conforming to the Roku Direct Publisher feed spec
     */
    fun generateFeed(baseUrl: String, apiKey: String, user: AppUser? = null): String {
        val nasRoot = TranscoderAgent.getNasRoot()

        val titles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { title -> user?.canSeeRating(title.content_rating) ?: true }
        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val episodes = Episode.findAll()
        val genres = Genre.findAll().associateBy { it.id }
        val titleGenres = TitleGenre.findAll()
        val castMembers = CastMember.findAll()

        // Group transcodes by title_id
        val transcodesByTitle = transcodes.groupBy { it.title_id }
        val episodesById = episodes.associateBy { it.id }
        val episodesByTitle = episodes.groupBy { it.title_id }
        val genresByTitle = titleGenres.groupBy { it.title_id }
        val castByTitle = castMembers.groupBy { it.title_id }

        // Load authenticated user's playback progress (transcode_id → position)
        val rokuProgress: Map<Long, Double> = if (user != null) {
            PlaybackProgress.findAll()
                .filter { it.user_id == user.id }
                .associate { it.transcode_id to it.position_seconds }
        } else emptyMap()

        val movies = mutableListOf<Map<String, Any?>>()
        val series = mutableListOf<Map<String, Any?>>()
        var latestUpdated: LocalDateTime? = null

        for (title in titles) {
            val titleTranscodes = transcodesByTitle[title.id] ?: continue
            val playable = titleTranscodes.filter { isPlayable(it, nasRoot) }
            if (playable.isEmpty()) continue

            // Track latest update
            if (latestUpdated == null || (title.updated_at != null && title.updated_at!! > latestUpdated)) {
                latestUpdated = title.updated_at
            }

            val titleGenreNames = genresByTitle[title.id]
                ?.mapNotNull { tg -> genres[tg.genre_id]?.name }
                ?: emptyList()

            val cast = castByTitle[title.id]
                ?.sortedBy { it.cast_order }
                ?.take(5)
                ?.map { it.name }
                ?: emptyList()

            val posterUrl = if (title.poster_path != null) {
                "$baseUrl/posters/w500/${title.id}?key=$apiKey"
            } else null

            val backdropUrl = if (title.backdrop_path != null) {
                "$baseUrl/backdrops/${title.id}?key=$apiKey"
            } else null

            if (title.media_type == MediaType.MOVIE.name) {
                val video = playable.first()
                val movieMap = buildMovieEntry(title, video, posterUrl, backdropUrl, titleGenreNames, cast, baseUrl, apiKey, rokuProgress, nasRoot)
                movies.add(movieMap)
            } else if (title.media_type == MediaType.TV.name) {
                val seriesMap = buildSeriesEntry(
                    title, playable, episodesById, episodesByTitle,
                    posterUrl, backdropUrl, titleGenreNames, cast, baseUrl, apiKey, rokuProgress, nasRoot
                )
                if (seriesMap != null) series.add(seriesMap)
            }
        }

        val feed = linkedMapOf<String, Any?>(
            "providerName" to "Media Manager",
            "lastUpdated" to formatIso(latestUpdated ?: LocalDateTime.now()),
            "language" to "en",
            "movies" to movies,
            "series" to series
        )

        return mapper.writeValueAsString(feed)
    }

    private fun buildMovieEntry(
        title: Title,
        transcode: Transcode,
        posterUrl: String?,
        backdropUrl: String?,
        genres: List<String>,
        cast: List<String>,
        baseUrl: String,
        apiKey: String,
        rokuProgress: Map<Long, Double>,
        nasRoot: String?
    ): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "id" to "title-${title.id}",
            "title" to title.name,
            "shortDescription" to (title.description ?: ""),
            "releaseDate" to formatReleaseDate(title.release_year),
            "genres" to genres.ifEmpty { listOf("Entertainment") }
        )
        if (title.content_rating != null) {
            entry["rating"] = linkedMapOf("rating" to title.content_rating, "ratingSource" to "MPAA")
        }
        if (posterUrl != null) {
            entry["thumbnail"] = posterUrl
        }
        if (backdropUrl != null) {
            entry["imageUrl"] = backdropUrl
        }
        if (cast.isNotEmpty()) {
            entry["tags"] = linkedMapOf("cast" to cast)
        }
        entry["content"] = buildContent(transcode, baseUrl, apiKey, rokuProgress, nasRoot)
        return entry
    }

    private fun buildSeriesEntry(
        title: Title,
        playable: List<Transcode>,
        episodesById: Map<Long?, Episode>,
        episodesByTitle: Map<Long, List<Episode>>,
        posterUrl: String?,
        backdropUrl: String?,
        genres: List<String>,
        cast: List<String>,
        baseUrl: String,
        apiKey: String,
        rokuProgress: Map<Long, Double>,
        nasRoot: String?
    ): Map<String, Any?>? {
        // Group playable transcodes by episode → by season
        val episodeTranscodes = playable.filter { it.episode_id != null }
        if (episodeTranscodes.isEmpty()) {
            // TV series with no episode-linked transcodes — treat as a single-item series
            // or skip. For now, include as a movie-like entry in movies would be odd, so skip.
            return null
        }

        // Group by season
        data class EpisodeEntry(val episode: Episode, val transcode: Transcode)

        val entries = episodeTranscodes.mapNotNull { tc ->
            val ep = episodesById[tc.episode_id] ?: return@mapNotNull null
            EpisodeEntry(ep, tc)
        }

        val bySeason = entries.groupBy { it.episode.season_number }
            .toSortedMap()

        val seasons = bySeason.map { (seasonNum, eps) ->
            val sortedEps = eps.sortedBy { it.episode.episode_number }
            linkedMapOf<String, Any?>(
                "seasonNumber" to seasonNum.toString(),
                "episodes" to sortedEps.map { (ep, tc) ->
                    val epEntry = linkedMapOf<String, Any?>(
                        "id" to "ep-${ep.id}",
                        "title" to (ep.name ?: "Episode ${ep.episode_number}"),
                        "episodeNumber" to ep.episode_number.toString(),
                        "shortDescription" to "",
                        "content" to buildContent(tc, baseUrl, apiKey, rokuProgress, nasRoot)
                    )
                    if (posterUrl != null) {
                        epEntry["thumbnail"] = posterUrl
                    }
                    epEntry
                }
            )
        }

        val seriesMap = linkedMapOf<String, Any?>(
            "id" to "title-${title.id}",
            "title" to title.name,
            "shortDescription" to (title.description ?: ""),
            "releaseDate" to formatReleaseDate(title.release_year),
            "genres" to genres.ifEmpty { listOf("Entertainment") },
            "seasons" to seasons
        )
        if (title.content_rating != null) {
            val ratingSource = if (title.media_type == MediaType.TV.name) "USA_PR" else "MPAA"
            seriesMap["rating"] = linkedMapOf("rating" to title.content_rating, "ratingSource" to ratingSource)
        }
        if (posterUrl != null) {
            seriesMap["thumbnail"] = posterUrl
        }
        if (backdropUrl != null) {
            seriesMap["imageUrl"] = backdropUrl
        }
        if (cast.isNotEmpty()) {
            seriesMap["tags"] = linkedMapOf("cast" to cast)
        }
        return seriesMap
    }

    private fun buildContent(
        transcode: Transcode,
        baseUrl: String,
        apiKey: String,
        rokuProgress: Map<Long, Double>,
        nasRoot: String?
    ): Map<String, Any?> {
        val streamUrl = "$baseUrl/stream/${transcode.id}?key=$apiKey"
        val quality = when (transcode.media_format) {
            MediaFormat.UHD_BLURAY.name -> "UHD"
            MediaFormat.DVD.name -> "SD"
            else -> "FHD"
        }
        val content = linkedMapOf<String, Any?>(
            "duration" to 0,
            "videos" to listOf(
                linkedMapOf(
                    "url" to streamUrl,
                    "quality" to quality,
                    "videoType" to "MP4"
                )
            )
        )
        // Include Roku user's resume position if they have saved progress
        val position = rokuProgress[transcode.id]
        if (position != null && position > 0) {
            content["playbackPosition"] = position.toInt()
        }
        // Include subtitle URL if an SRT file exists alongside the playable MP4
        if (hasSubtitleFile(transcode, nasRoot)) {
            content["subtitleUrl"] = "$baseUrl/stream/${transcode.id}/subs.srt?key=$apiKey"
        }
        // Include BIF URL for Roku trick play thumbnails
        if (hasSpriteSheets(transcode, nasRoot)) {
            content["bifUrl"] = "$baseUrl/stream/${transcode.id}/trickplay.bif?key=$apiKey"
        }
        return content
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

    private fun hasSpriteSheets(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        val mp4File = when {
            ext in DIRECT_EXTENSIONS -> File(filePath)
            ext in TRANSCODE_EXTENSIONS && nasRoot != null -> TranscoderAgent.getForBrowserPath(nasRoot, filePath)
            else -> return false
        }
        if (!mp4File.exists()) return false
        val vttFile = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".thumbs.vtt")
        return vttFile.exists()
    }

    private fun isPlayable(transcode: Transcode, nasRoot: String?): Boolean {
        val filePath = transcode.file_path ?: return false
        val ext = File(filePath).extension.lowercase()
        return when {
            ext in DIRECT_EXTENSIONS -> File(filePath).exists()
            ext in TRANSCODE_EXTENSIONS -> {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
            }
            else -> false
        }
    }

    private fun formatReleaseDate(year: Int?): String {
        return if (year != null) "$year-01-01" else "2000-01-01"
    }

    private fun formatIso(dateTime: LocalDateTime): String {
        return dateTime.atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
