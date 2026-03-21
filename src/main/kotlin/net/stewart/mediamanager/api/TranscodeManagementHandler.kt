package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.linkDiscoveredFileToTitle
import net.stewart.mediamanager.service.FuzzyMatchService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.TranscoderAgent
import org.slf4j.LoggerFactory

/**
 * Handles /api/v1/admin/transcodes/... endpoints (admin-only):
 * - GET /transcodes/linked — paginated linked transcodes with file status
 * - POST /transcodes/{id}/unlink — unlink a transcode from its title
 * - GET /transcodes/unmatched — unmatched NAS files with suggestions
 * - POST /transcodes/unmatched/{id}/accept — accept top suggestion
 * - POST /transcodes/unmatched/{id}/ignore — mark as ignored
 * - POST /transcodes/unmatched/{id}/link — manual link to title
 */
object TranscodeManagementHandler {

    private val log = LoggerFactory.getLogger(TranscodeManagementHandler::class.java)

    private val UNLINK = Regex("(\\d+)/unlink")
    private val UNMATCHED_ACCEPT = Regex("unmatched/(\\d+)/accept")
    private val UNMATCHED_IGNORE = Regex("unmatched/(\\d+)/ignore")
    private val UNMATCHED_LINK = Regex("unmatched/(\\d+)/link")

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val method = req.method

        when {
            path == "linked" && method == "GET" -> handleLinked(req, resp, mapper)
            path == "unmatched" && method == "GET" -> handleUnmatched(resp, mapper)
            method == "POST" -> {
                val unlinkMatch = UNLINK.matchEntire(path)
                val acceptMatch = UNMATCHED_ACCEPT.matchEntire(path)
                val ignoreMatch = UNMATCHED_IGNORE.matchEntire(path)
                val linkMatch = UNMATCHED_LINK.matchEntire(path)

                when {
                    unlinkMatch != null -> handleUnlink(resp, mapper, unlinkMatch.groupValues[1].toLong())
                    acceptMatch != null -> handleAccept(resp, mapper, acceptMatch.groupValues[1].toLong())
                    ignoreMatch != null -> handleIgnore(resp, mapper, ignoreMatch.groupValues[1].toLong())
                    linkMatch != null -> handleLink(req, resp, mapper, linkMatch.groupValues[1].toLong())
                    else -> {
                        ApiV1Servlet.sendError(resp, 404, "not_found")
                        MetricsRegistry.countHttpResponse("api_v1", 404)
                    }
                }
            }
            else -> {
                ApiV1Servlet.sendError(resp, 404, "not_found")
                MetricsRegistry.countHttpResponse("api_v1", 404)
            }
        }
    }

    // --- Linked Transcodes ---

    private fun handleLinked(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val page = (req.getParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val limit = (req.getParameter("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val nasRoot = TranscoderAgent.getNasRoot()

        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val titles = Title.findAll().associateBy { it.id }
        val episodes = Episode.findAll().associateBy { it.id }

        val rows = transcodes.mapNotNull { tc ->
            val title = titles[tc.title_id] ?: return@mapNotNull null
            val episode = tc.episode_id?.let { episodes[it] }
            val posterUrl = if (title.poster_path != null) "/posters/w185/${title.id}" else null

            mapOf(
                "transcode_id" to tc.id,
                "title_id" to title.id,
                "title_name" to title.name,
                "media_type" to title.media_type,
                "poster_url" to posterUrl,
                "file_path" to tc.file_path,
                "media_format" to tc.media_format,
                "season_number" to episode?.season_number,
                "episode_number" to episode?.episode_number,
                "episode_name" to episode?.name,
                "retranscode_requested" to tc.retranscode_requested
            )
        }.sortedBy { (it["title_name"] as String).lowercase() }

        val total = rows.size
        val totalPages = if (total == 0) 0 else (total + limit - 1) / limit
        val pageRows = rows.drop((page - 1) * limit).take(limit)

        ApiV1Servlet.sendJson(resp, 200, mapOf(
            "transcodes" to pageRows, "total" to total, "page" to page, "limit" to limit, "total_pages" to totalPages
        ), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Unlink ---

    private fun handleUnlink(resp: HttpServletResponse, mapper: ObjectMapper, transcodeId: Long) {
        val tc = Transcode.findById(transcodeId)
        if (tc == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        // Reset the discovered file back to UNMATCHED
        val df = DiscoveredFile.findAll().firstOrNull { it.file_path == tc.file_path }
        if (df != null) {
            df.match_status = DiscoveredFileStatus.UNMATCHED.name
            df.matched_title_id = null
            df.matched_episode_id = null
            df.match_method = null
            df.save()
        }

        // Delete related leases
        TranscodeLease.findAll().filter { it.transcode_id == transcodeId }.forEach { it.delete() }

        // Delete the transcode (and its episode if it's the only transcode referencing it)
        val episodeId = tc.episode_id
        tc.delete()

        if (episodeId != null) {
            val otherRefs = Transcode.findAll().any { it.episode_id == episodeId }
            if (!otherRefs) {
                Episode.findById(episodeId)?.delete()
            }
        }

        log.info("Transcode unlinked via API: id={}", transcodeId)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Unmatched Files ---

    private fun handleUnmatched(resp: HttpServletResponse, mapper: ObjectMapper) {
        val unmatched = DiscoveredFile.findAll()
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
            .sortedBy { it.file_name.lowercase() }

        val allTitles = Title.findAll().filter {
            it.enrichment_status == EnrichmentStatus.ENRICHED.name && !it.hidden
        }

        val items = unmatched.map { df ->
            val suggestions = if (!df.parsed_title.isNullOrBlank()) {
                FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles, maxResults = 3)
                    .map { s -> mapOf("title_id" to s.title.id, "title_name" to s.title.name, "score" to s.score) }
            } else emptyList()

            mapOf(
                "id" to df.id,
                "file_name" to df.file_name,
                "directory" to df.directory,
                "media_type" to df.media_type,
                "parsed_title" to df.parsed_title,
                "parsed_year" to df.parsed_year,
                "parsed_season" to df.parsed_season,
                "parsed_episode" to df.parsed_episode,
                "suggestions" to suggestions
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf("unmatched" to items, "total" to items.size), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Accept Suggestion ---

    private fun handleAccept(resp: HttpServletResponse, mapper: ObjectMapper, fileId: Long) {
        val df = DiscoveredFile.findById(fileId)
        if (df == null || df.match_status != DiscoveredFileStatus.UNMATCHED.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        val allTitles = Title.findAll().filter {
            it.enrichment_status == EnrichmentStatus.ENRICHED.name && !it.hidden
        }
        val suggestions = if (!df.parsed_title.isNullOrBlank()) {
            FuzzyMatchService.findSuggestions(df.parsed_title!!, allTitles, maxResults = 1)
        } else emptyList()

        if (suggestions.isEmpty()) {
            ApiV1Servlet.sendError(resp, 404, "no_suggestion")
            MetricsRegistry.countHttpResponse("api_v1", 404)
            return
        }

        val title = suggestions.first().title
        val count = linkDiscoveredFileToTitle(df, title)
        log.info("API accepted suggestion: file={} -> title='{}' ({} linked)", fileId, title.name, count)

        ApiV1Servlet.sendJson(resp, 200, mapOf("linked" to count, "title_name" to title.name), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Ignore File ---

    private fun handleIgnore(resp: HttpServletResponse, mapper: ObjectMapper, fileId: Long) {
        val df = DiscoveredFile.findById(fileId)
        if (df == null || df.match_status != DiscoveredFileStatus.UNMATCHED.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        df.match_status = DiscoveredFileStatus.IGNORED.name
        df.save()
        log.info("API ignored unmatched file: id={}", fileId)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Manual Link ---

    private fun handleLink(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, fileId: Long) {
        val df = DiscoveredFile.findById(fileId)
        if (df == null || df.match_status != DiscoveredFileStatus.UNMATCHED.name) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }
        val titleId = body.get("title_id")?.asLong()
        if (titleId == null) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }
        val title = Title.findById(titleId)
        if (title == null) {
            ApiV1Servlet.sendError(resp, 404, "title_not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        val count = linkDiscoveredFileToTitle(df, title)
        log.info("API manual link: file={} -> title='{}' ({} linked)", fileId, title.name, count)

        ApiV1Servlet.sendJson(resp, 200, mapOf("linked" to count, "title_name" to title.name), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }
}
