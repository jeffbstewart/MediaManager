package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime

/**
 * Builds the Roku home screen carousel feed.
 *
 * Returns a list of named carousels, each containing items with enough data
 * for the Roku to render poster grids and navigate to playback.
 */
object RokuHomeService {

    private val log = LoggerFactory.getLogger(RokuHomeService::class.java)

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    private const val MAX_CAROUSEL_ITEMS = 25

    data class CarouselItem(
        val titleId: Long,
        val name: String,
        val posterUrl: String?,
        val year: Int?,
        val mediaType: String,
        val quality: String,
        val contentRating: String?,
        val transcodeId: Long?,
        val subtitleUrl: String? = null,
        val bifUrl: String? = null,
        val resumePosition: Int? = null,
        val wishFulfilled: Boolean = false
    )

    data class Carousel(
        val name: String,
        val items: List<CarouselItem>
    )

    data class HomeFeed(
        val carousels: List<Carousel>
    )

    fun generateHomeFeed(baseUrl: String, apiKey: String, user: AppUser): HomeFeed {
        val nasRoot = TranscoderAgent.getNasRoot()

        val titles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = titles.associateBy { it.id }

        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val playableTranscodes = transcodes.filter { isPlayable(it, nasRoot) }
        val playableByTitle = playableTranscodes.groupBy { it.title_id }

        // Only titles that have at least one playable transcode
        // For TV titles, require at least one episode-linked transcode
        val playableTitles = titles.filter { title ->
            val titleTranscodes = playableByTitle[title.id] ?: return@filter false
            if (title.media_type == MediaType.TV.name) {
                titleTranscodes.any { it.episode_id != null }
            } else {
                true
            }
        }

        // User's playback progress
        val allProgress = PlaybackProgress.findAll()
            .filter { it.user_id == user.id }
        val progressByTranscode = allProgress.associate { it.transcode_id to it }

        val carousels = mutableListOf<Carousel>()

        // 1. Resume Playing — titles with saved progress for this user
        val resumeCarousel = buildResumeCarousel(playableTitles, playableByTitle, progressByTranscode, baseUrl, apiKey, nasRoot)
        if (resumeCarousel.items.isNotEmpty()) {
            carousels.add(resumeCarousel)
        }

        // 2. Recently Added — most recently created transcodes (with wish-fulfilled badges)
        val playableTitleIds = playableTitles.mapNotNull { it.id }.toSet()
        val recentCarousel = buildRecentlyAddedCarousel(playableTranscodes, titlesById, playableTitleIds, baseUrl, apiKey, nasRoot, user)
        if (recentCarousel.items.isNotEmpty()) {
            carousels.add(recentCarousel)
        }

        // 3. Movies — all playable movies by popularity
        val movieCarousel = buildTypeCarousel("Movies", MediaType.MOVIE.name, playableTitles, playableByTitle, baseUrl, apiKey, nasRoot)
        if (movieCarousel.items.isNotEmpty()) {
            carousels.add(movieCarousel)
        }

        // 4. TV Series — all playable TV by popularity
        val tvCarousel = buildTypeCarousel("TV Series", MediaType.TV.name, playableTitles, playableByTitle, baseUrl, apiKey, nasRoot)
        if (tvCarousel.items.isNotEmpty()) {
            carousels.add(tvCarousel)
        }

        log.info("Roku home feed: {} carousels, {} total items",
            carousels.size, carousels.sumOf { it.items.size })

        return HomeFeed(carousels)
    }

    private fun buildResumeCarousel(
        playableTitles: List<Title>,
        playableByTitle: Map<Long, List<Transcode>>,
        progressByTranscode: Map<Long, PlaybackProgress>,
        baseUrl: String,
        apiKey: String,
        nasRoot: String?
    ): Carousel {
        // Find titles where the user has progress on a playable transcode
        data class ResumeEntry(val title: Title, val transcode: Transcode, val progress: PlaybackProgress)

        val entries = mutableListOf<ResumeEntry>()
        for (title in playableTitles) {
            val titleTranscodes = playableByTitle[title.id] ?: continue
            for (tc in titleTranscodes) {
                val progress = progressByTranscode[tc.id]
                if (progress != null && progress.position_seconds > 0) {
                    entries.add(ResumeEntry(title, tc, progress))
                    break // one entry per title
                }
            }
        }

        // Sort by most recently updated progress
        val sorted = entries
            .sortedByDescending { it.progress.updated_at }
            .take(MAX_CAROUSEL_ITEMS)

        val items = sorted.map { (title, transcode, progress) ->
            buildItem(title, transcode, baseUrl, apiKey, nasRoot, progress.position_seconds.toInt())
        }

        return Carousel("Resume Playing", items)
    }

    private fun buildRecentlyAddedCarousel(
        playableTranscodes: List<Transcode>,
        titlesById: Map<Long?, Title>,
        playableTitleIds: Set<Long>,
        baseUrl: String,
        apiKey: String,
        nasRoot: String?,
        user: AppUser
    ): Carousel {
        // Find fulfilled wishes for this user to mark with badge
        val fulfilledWishes = WishListItem.findAll()
            .filter { it.user_id == user.id && it.status == WishStatus.FULFILLED.name && it.wish_type == WishType.MEDIA.name }
        val fulfilledTmdbKeys = fulfilledWishes.mapNotNull { it.tmdbKey() }.toSet()

        // Most recently created playable transcodes, deduplicated by title
        // Only includes titles that passed the playability filter (TV requires episode-linked transcodes)
        val seen = mutableSetOf<Long>()
        val items = mutableListOf<CarouselItem>()

        val sorted = playableTranscodes.sortedByDescending { it.created_at }
        for (tc in sorted) {
            if (items.size >= MAX_CAROUSEL_ITEMS) break
            val title = titlesById[tc.title_id] ?: continue
            if (title.id!! !in playableTitleIds) continue
            if (title.id!! in seen) continue
            seen.add(title.id!!)

            val isFulfilled = title.tmdbKey()?.let { it in fulfilledTmdbKeys } ?: false
            items.add(buildItem(title, tc, baseUrl, apiKey, nasRoot, wishFulfilled = isFulfilled))
        }

        return Carousel("Recently Added", items)
    }

    private fun buildTypeCarousel(
        name: String,
        mediaType: String,
        playableTitles: List<Title>,
        playableByTitle: Map<Long, List<Transcode>>,
        baseUrl: String,
        apiKey: String,
        nasRoot: String?
    ): Carousel {
        val filtered = playableTitles
            .filter { it.media_type == mediaType }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_CAROUSEL_ITEMS)

        val items = filtered.map { title ->
            val transcode = playableByTitle[title.id]?.firstOrNull()
            buildItem(title, transcode, baseUrl, apiKey, nasRoot)
        }

        return Carousel(name, items)
    }

    private fun buildItem(
        title: Title,
        transcode: Transcode?,
        baseUrl: String,
        apiKey: String,
        nasRoot: String?,
        resumePosition: Int? = null,
        wishFulfilled: Boolean = false
    ): CarouselItem {
        val posterUrl = if (title.poster_path != null) {
            "$baseUrl/posters/w500/${title.id}?key=$apiKey"
        } else null

        val quality = when (transcode?.media_format) {
            MediaFormat.UHD_BLURAY.name -> "UHD"
            MediaFormat.DVD.name -> "SD"
            else -> "FHD"
        }

        val subtitleUrl = if (transcode != null && hasSubtitleFile(transcode, nasRoot)) {
            "$baseUrl/stream/${transcode.id}/subs.srt?key=$apiKey"
        } else null

        val bifUrl = if (transcode != null && hasSpriteSheets(transcode, nasRoot)) {
            "$baseUrl/stream/${transcode.id}/trickplay.bif?key=$apiKey"
        } else null

        return CarouselItem(
            titleId = title.id!!,
            name = title.name,
            posterUrl = posterUrl,
            year = title.release_year,
            mediaType = title.media_type,
            quality = quality,
            contentRating = title.content_rating,
            transcodeId = transcode?.id,
            subtitleUrl = subtitleUrl,
            bifUrl = bifUrl,
            resumePosition = resumePosition,
            wishFulfilled = wishFulfilled
        )
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
        return File(mp4File.parentFile, mp4File.nameWithoutExtension + ".thumbs.vtt").exists()
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
}
