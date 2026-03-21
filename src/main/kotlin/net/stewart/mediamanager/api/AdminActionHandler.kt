package net.stewart.mediamanager.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import org.slf4j.LoggerFactory

/**
 * Handles miscellaneous admin action endpoints:
 * - POST /admin/scan-nas — trigger NAS scan
 * - POST /admin/clear-failures — clear all failed leases
 * - GET /admin/data-quality — titles with enrichment issues
 * - PUT /admin/titles/{id}/metadata — edit title metadata
 * - DELETE /admin/titles/{id} — delete title
 * - POST /admin/titles/{id}/re-enrich — re-trigger TMDB enrichment
 * - GET /admin/settings — current config
 * - PUT /admin/settings — update config
 * - POST /admin/tags — create tag
 * - PUT /admin/tags/{id} — edit tag
 * - DELETE /admin/tags/{id} — delete tag
 */
object AdminActionHandler {

    private val log = LoggerFactory.getLogger(AdminActionHandler::class.java)

    private val TITLE_METADATA = Regex("titles/(\\d+)/metadata")
    private val TITLE_DELETE = Regex("titles/(\\d+)")
    private val TITLE_RE_ENRICH = Regex("titles/(\\d+)/re-enrich")
    private val TAG_ID = Regex("tags/(\\d+)")

    fun handle(req: HttpServletRequest, resp: HttpServletResponse, path: String, mapper: ObjectMapper) {
        val method = req.method

        when {
            path == "scan-nas" && method == "POST" -> handleScanNas(resp, mapper)
            path == "clear-failures" && method == "POST" -> handleClearFailures(resp, mapper)
            path == "data-quality" && method == "GET" -> handleDataQuality(req, resp, mapper)
            path == "settings" && method == "GET" -> handleGetSettings(resp, mapper)
            path == "settings" && method == "PUT" -> handleUpdateSettings(req, resp, mapper)
            path == "tags" && method == "POST" -> handleCreateTag(req, resp, mapper)
            else -> {
                val metadataMatch = TITLE_METADATA.matchEntire(path)
                val reEnrichMatch = TITLE_RE_ENRICH.matchEntire(path)
                val titleDeleteMatch = TITLE_DELETE.matchEntire(path)
                val tagMatch = TAG_ID.matchEntire(path)

                when {
                    reEnrichMatch != null && method == "POST" -> {
                        val titleId = reEnrichMatch.groupValues[1].toLong()
                        handleReEnrich(resp, mapper, titleId)
                    }
                    metadataMatch != null && method == "PUT" -> {
                        val titleId = metadataMatch.groupValues[1].toLong()
                        handleEditMetadata(req, resp, mapper, titleId)
                    }
                    titleDeleteMatch != null && method == "DELETE" && path.startsWith("titles/") -> {
                        val titleId = titleDeleteMatch.groupValues[1].toLong()
                        handleDeleteTitle(resp, mapper, titleId)
                    }
                    tagMatch != null && method == "PUT" -> {
                        val tagId = tagMatch.groupValues[1].toLong()
                        handleUpdateTag(req, resp, mapper, tagId)
                    }
                    tagMatch != null && method == "DELETE" -> {
                        val tagId = tagMatch.groupValues[1].toLong()
                        handleDeleteTag(resp, mapper, tagId)
                    }
                    else -> {
                        ApiV1Servlet.sendError(resp, 404, "not_found")
                        MetricsRegistry.countHttpResponse("api_v1", 404)
                    }
                }
            }
        }
    }

    // --- NAS Scan ---

    private fun handleScanNas(resp: HttpServletResponse, mapper: ObjectMapper) {
        if (NasScannerService.isRunning()) {
            ApiV1Servlet.sendError(resp, 409, "scan_already_running")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }
        NasScannerService.scan()
        ApiV1Servlet.sendJson(resp, 202, mapOf("started" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 202)
    }

    // --- Clear Failures ---

    private fun handleClearFailures(resp: HttpServletResponse, mapper: ObjectMapper) {
        val cleared = TranscodeLeaseService.clearAllFailures()
        ApiV1Servlet.sendJson(resp, 200, mapOf("cleared" to cleared), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Data Quality ---

    private fun handleDataQuality(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val page = (req.getParameter("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val limit = (req.getParameter("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val statusFilter = req.getParameter("status") // e.g., "FAILED", "PENDING"

        var titles = Title.findAll()

        if (statusFilter != null) {
            titles = titles.filter { it.enrichment_status == statusFilter }
        } else {
            // Default: show titles that need attention (not enriched)
            titles = titles.filter {
                it.enrichment_status != EnrichmentStatus.ENRICHED.name || it.hidden
            }
        }

        titles = titles.sortedWith(
            compareBy<Title> { it.enrichment_status == EnrichmentStatus.ENRICHED.name }
                .thenByDescending { it.created_at }
        )

        val total = titles.size
        val totalPages = if (total == 0) 0 else (total + limit - 1) / limit
        val pageTitles = titles.drop((page - 1) * limit).take(limit)

        val items = pageTitles.map { t ->
            val posterUrl = if (t.poster_path != null) "/posters/w185/${t.id}" else null
            mapOf(
                "id" to t.id,
                "name" to t.name,
                "media_type" to t.media_type,
                "enrichment_status" to t.enrichment_status,
                "tmdb_id" to t.tmdb_id,
                "release_year" to t.release_year,
                "content_rating" to t.content_rating,
                "poster_url" to posterUrl,
                "hidden" to t.hidden,
                "created_at" to t.created_at?.toString()
            )
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf(
            "titles" to items, "total" to total, "page" to page, "limit" to limit, "total_pages" to totalPages
        ), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Title Metadata Edit ---

    private fun handleEditMetadata(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val title = Title.findById(titleId)
        if (title == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }

        // Apply only provided fields
        body.get("name")?.asText()?.let { title.name = it.take(500) }
        body.get("release_year")?.asInt()?.let { title.release_year = it }
        body.get("content_rating")?.asText()?.let { title.content_rating = it.take(20) }
        body.get("description")?.asText()?.let { title.description = it.take(5000) }
        body.get("hidden")?.asBoolean()?.let { title.hidden = it }
        body.get("tmdb_id")?.asInt()?.let { title.tmdb_id = it }
        title.updated_at = java.time.LocalDateTime.now()
        title.save()

        SearchIndexService.onTitleChanged(titleId)
        log.info("Title metadata updated via API: id={} name=\"{}\"", titleId, title.name)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Delete Title ---

    private fun handleDeleteTitle(resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val title = Title.findById(titleId)
        if (title == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }

        // Delete related data
        TitleTag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        TitleGenre.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        CastMember.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        Transcode.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        Episode.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        MediaItemTitle.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        UserTitleFlag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        title.delete()

        SearchIndexService.onTitleDeleted(titleId)
        log.info("Title deleted via API: id={} name=\"{}\"", titleId, title.name)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Re-Enrich ---

    private fun handleReEnrich(resp: HttpServletResponse, mapper: ObjectMapper, titleId: Long) {
        val title = Title.findById(titleId)
        if (title == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        title.enrichment_status = EnrichmentStatus.PENDING.name
        title.retry_after = null
        title.save()
        log.info("Re-enrichment triggered via API: id={} name=\"{}\"", titleId, title.name)
        ApiV1Servlet.sendJson(resp, 200, mapOf("re_enrich_queued" to true), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    // --- Settings ---

    private val VISIBLE_SETTINGS = setOf(
        "nas_root_path", "roku_base_url", "personal_video_enabled", "personal_video_directory",
        "lease_duration_minutes", "for_mobile_enabled",
        "keepa_enabled", "keepa_tokens_per_minute"
    )

    private fun handleGetSettings(resp: HttpServletResponse, mapper: ObjectMapper) {
        val configs = AppConfig.findAll()
            .filter { it.config_key in VISIBLE_SETTINGS }
            .associate { it.config_key to it.config_val }

        val buddyKeys = BuddyKeyService.getAllKeys().map {
            mapOf("id" to it.id, "name" to it.name, "created_at" to it.created_at?.toString())
        }

        ApiV1Servlet.sendJson(resp, 200, mapOf(
            "settings" to configs,
            "buddy_keys" to buddyKeys
        ), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 200)
    }

    private fun handleUpdateSettings(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }

        val fields = body.fields()
        while (fields.hasNext()) {
            val (key, value) = fields.next()
            if (key !in VISIBLE_SETTINGS) continue
            val strVal = if (value.isNull) null else value.asText()
            val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
            if (existing != null) {
                existing.config_val = strVal
                existing.save()
            } else {
                AppConfig(config_key = key, config_val = strVal).save()
            }
        }

        log.info("Settings updated via API")
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    // --- Tags ---

    private fun handleCreateTag(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }

        val name = body.get("name")?.asText()
        val color = body.get("color")?.asText()
        if (name.isNullOrBlank() || color.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }
        if (!TagService.isNameUnique(name)) {
            ApiV1Servlet.sendError(resp, 409, "name_taken")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }

        val tag = TagService.createTag(name, color)
        ApiV1Servlet.sendJson(resp, 201, mapOf("id" to tag.id, "name" to tag.name, "color" to tag.bg_color), mapper)
        MetricsRegistry.countHttpResponse("api_v1", 201)
    }

    private fun handleUpdateTag(req: HttpServletRequest, resp: HttpServletResponse, mapper: ObjectMapper, tagId: Long) {
        val body = try { mapper.readTree(req.reader) } catch (_: Exception) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request"); MetricsRegistry.countHttpResponse("api_v1", 400); return
        }

        val name = body.get("name")?.asText()
        val color = body.get("color")?.asText()
        if (name.isNullOrBlank() || color.isNullOrBlank()) {
            ApiV1Servlet.sendError(resp, 400, "invalid_request")
            MetricsRegistry.countHttpResponse("api_v1", 400)
            return
        }
        if (!TagService.isNameUnique(name, tagId)) {
            ApiV1Servlet.sendError(resp, 409, "name_taken")
            MetricsRegistry.countHttpResponse("api_v1", 409)
            return
        }

        val tag = TagService.updateTag(tagId, name, color)
        if (tag == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }

    private fun handleDeleteTag(resp: HttpServletResponse, mapper: ObjectMapper, tagId: Long) {
        val tag = Tag.findById(tagId)
        if (tag == null) {
            ApiV1Servlet.sendError(resp, 404, "not_found"); MetricsRegistry.countHttpResponse("api_v1", 404); return
        }
        TagService.deleteTag(tagId)
        resp.status = 204
        MetricsRegistry.countHttpResponse("api_v1", 204)
    }
}
