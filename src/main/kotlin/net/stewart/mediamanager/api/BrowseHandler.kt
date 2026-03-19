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
 * Handles browse/landing page endpoints:
 * - GET /catalog/actors/{tmdbPersonId}
 * - GET /catalog/collections/{tmdbCollectionId}
 * - GET /catalog/tags/{id}
 * - GET /catalog/genres/{id}
 */
object BrowseHandler {

    private val DIRECT_EXTENSIONS = setOf("mp4", "m4v")
    private val TRANSCODE_EXTENSIONS = setOf("mkv", "avi")

    fun handleActor(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, tmdbPersonId: Int) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val nasRoot = TranscoderAgent.getNasRoot()
        val castMembers = CastMember.findAll().filter { it.tmdb_person_id == tmdbPersonId }
        if (castMembers.isEmpty()) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val actorName = castMembers.first().name
        val headshotMember = castMembers.maxByOrNull { it.popularity ?: 0.0 }
        val headshotUrl = if (headshotMember?.headshot_cache_id != null) "/headshots/${headshotMember.id}" else null

        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesById = allTitles.associateBy { it.id }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        // Deduplicate by TMDB identity
        val seenTmdbKeys = mutableSetOf<TmdbId>()
        val titles = mutableListOf<ApiTitle>()

        for (cm in castMembers.sortedByDescending { titlesById[it.title_id]?.popularity ?: 0.0 }) {
            val title = titlesById[cm.title_id] ?: continue
            val tmdbKey = title.tmdbKey()
            if (tmdbKey != null && tmdbKey in seenTmdbKeys) continue
            if (tmdbKey != null) seenTmdbKeys.add(tmdbKey)

            val tc = playableByTitle[title.id]?.firstOrNull()
            val playable = if (tc != null) {
                if (title.media_type == MediaType.TV.name) playableByTitle[title.id]?.any { it.episode_id != null } == true
                else true
            } else false
            if (!playable) continue

            val posterUrl = if (title.poster_path != null) "/posters/w500/${title.id}" else null
            titles.add(ApiTitle(
                id = title.id!!, name = title.name, mediaType = title.media_type,
                year = title.release_year, description = title.description,
                posterUrl = posterUrl, backdropUrl = null,
                contentRating = title.content_rating, popularity = title.popularity,
                quality = qualityLabel(tc!!), playable = true, transcodeId = tc.id,
                tmdbId = title.tmdb_id, tmdbCollectionId = title.tmdb_collection_id,
                tmdbCollectionName = title.tmdb_collection_name
            ))
        }

        ApiV1Servlet.sendJson(resp, 200, ApiActorDetail(actorName, headshotUrl, titles), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    fun handleCollection(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, tmdbCollectionId: Int) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val nasRoot = TranscoderAgent.getNasRoot()
        val coll = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == tmdbCollectionId }
        if (coll == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == coll.id }
            .sortedBy { it.position }

        val allTitles = Title.findAll()
            .filter { !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val titlesByTmdbId = allTitles
            .filter { it.media_type == MediaType.MOVIE.name && it.tmdb_id != null }
            .associateBy { it.tmdb_id!! }

        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        val collPosterUrl = if (coll.poster_path != null) "/collection-posters/${coll.tmdb_collection_id}" else null

        val items = parts.map { part ->
            val title = titlesByTmdbId[part.tmdb_movie_id]
            val tc = if (title != null) playableByTitle[title.id]?.firstOrNull() else null
            val posterUrl = when {
                title?.poster_path != null -> "/posters/w500/${title.id}"
                part.poster_path != null -> "https://image.tmdb.org/t/p/w500${part.poster_path}"
                else -> null
            }
            val year = part.release_date?.take(4)?.toIntOrNull() ?: title?.release_year

            ApiCollectionItem(
                tmdbMovieId = part.tmdb_movie_id,
                name = title?.name ?: part.title,
                posterUrl = posterUrl,
                year = year,
                owned = title != null,
                playable = tc != null,
                titleId = title?.id,
                quality = tc?.let { qualityLabel(it) },
                contentRating = title?.content_rating,
                transcodeId = tc?.id
            )
        }

        ApiV1Servlet.sendJson(resp, 200, ApiCollectionDetail(coll.name, collPosterUrl, items), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    fun handleTag(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, tagId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val tag = Tag.findAll().firstOrNull { it.id == tagId }
        if (tag == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val titleIds = TitleTag.findAll().filter { it.tag_id == tagId }.map { it.title_id }.toSet()
        val titles = buildTitleList(titleIds, user)
        ApiV1Servlet.sendJson(resp, 200, ApiTagDetail(tag.name, tag.bg_color, titles), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    fun handleGenre(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, genreId: Long) {
        val user = ApiV1Servlet.requireAuth(req, resp) ?: return

        val genre = Genre.findAll().firstOrNull { it.id == genreId }
        if (genre == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val titleIds = TitleGenre.findAll().filter { it.genre_id == genreId }.map { it.title_id }.toSet()
        val titles = buildTitleList(titleIds, user)
        ApiV1Servlet.sendJson(resp, 200, ApiGenreDetail(genre.name, titles), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun buildTitleList(titleIds: Set<Long>, user: AppUser): List<ApiTitle> {
        val nasRoot = TranscoderAgent.getNasRoot()
        val allTitles = Title.findAll()
            .filter { it.id in titleIds && !it.hidden && it.enrichment_status == EnrichmentStatus.ENRICHED.name }
            .filter { user.canSeeRating(it.content_rating) }
        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val playableByTitle = allTranscodes.filter { isPlayable(it, nasRoot) }.groupBy { it.title_id }

        return allTitles
            .filter { title ->
                val tcs = playableByTitle[title.id] ?: return@filter false
                if (title.media_type == MediaType.TV.name) tcs.any { it.episode_id != null } else true
            }
            .sortedByDescending { it.popularity ?: 0.0 }
            .map { title ->
                val tc = playableByTitle[title.id]?.firstOrNull()
                val posterUrl = if (title.poster_path != null) "/posters/w500/${title.id}" else null
                ApiTitle(
                    id = title.id!!, name = title.name, mediaType = title.media_type,
                    year = title.release_year, description = title.description,
                    posterUrl = posterUrl, backdropUrl = null,
                    contentRating = title.content_rating, popularity = title.popularity,
                    quality = tc?.let { qualityLabel(it) }, playable = tc != null,
                    transcodeId = tc?.id, tmdbId = title.tmdb_id,
                    tmdbCollectionId = title.tmdb_collection_id,
                    tmdbCollectionName = title.tmdb_collection_name
                )
            }
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
}
