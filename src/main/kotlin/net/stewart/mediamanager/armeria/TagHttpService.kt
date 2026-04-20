package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import net.stewart.mediamanager.service.AutoTagApplicator
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * REST endpoints for browsing tags in the Angular web app.
 */
@Blocking
class TagHttpService {

    private val gson = Gson()

    /** Returns all tags with title counts. */
    @Get("/api/v2/catalog/tags")
    fun listTags(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val tags = TagService.getAllTags()
        val titleCounts = TagService.getTagTitleCounts()

        val items = tags.map { tag ->
            mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "bg_color" to tag.bg_color,
                "text_color" to tag.textColor(),
                "title_count" to (titleCounts[tag.id] ?: 0)
            )
        }

        return jsonResponse(gson.toJson(mapOf("tags" to items, "total" to items.size)))
    }

    /** Returns titles for a specific tag as poster cards. */
    @Get("/api/v2/catalog/tags/{tagId}")
    fun tagDetail(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val tag = net.stewart.mediamanager.entity.Tag.findById(tagId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val hiddenIds = UserTitleFlag.findAll()
            .filter { it.user_id == user.id && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()

        val taggedTitleIds = TagService.getTitleIdsForTags(setOf(tagId))
        val allTitles = Title.findAll().associateBy { it.id }

        // Tag detail historically only knew about video titles. Tags now
        // span albums and books too, and each media type has its own
        // notion of "playable" + its own card metadata. We compute all
        // three in one pass so the client can pick the right card shape.
        val nasRoot = TranscoderAgent.getNasRoot()
        val transcodesByTitle = Transcode.findAll()
            .filter { it.file_path != null }
            .groupBy { it.title_id }
        val progressByTitle = PlaybackProgressService.getProgressByTitleForUser(user.id!!)

        // Album-side: a title is playable when at least one of its tracks
        // has a ripped file on disk. Primary artist comes from the first
        // title_artist row.
        val tracksByTitle = Track.findAll()
            .filter { !it.file_path.isNullOrBlank() }
            .groupBy { it.title_id }
        val primaryArtistByTitle: Map<Long, Artist> = run {
            val links = TitleArtist.findAll().filter { it.artist_order == 0 }
            val artists = Artist.findAll().filter { it.id in links.map { l -> l.artist_id }.toSet() }
                .associateBy { it.id }
            links.mapNotNull { l -> artists[l.artist_id]?.let { l.title_id to it } }.toMap()
        }

        // Book-side: a title is "playable" (readable) when at least one
        // owned MediaItem for it has a digital file (epub / pdf / m4b).
        val mediaItemsById = MediaItem.findAll().associateBy { it.id }
        val digitalTitleIds: Set<Long> = MediaItemTitle.findAll()
            .filter { mediaItemsById[it.media_item_id]?.file_path?.isNotBlank() == true }
            .map { it.title_id }
            .toSet()
        val primaryAuthorByTitle: Map<Long, Author> = run {
            val links = TitleAuthor.findAll().filter { it.author_order == 0 }
            val authors = Author.findAll().filter { it.id in links.map { l -> l.author_id }.toSet() }
                .associateBy { it.id }
            links.mapNotNull { l -> authors[l.author_id]?.let { l.title_id to it } }.toMap()
        }

        val titles = taggedTitleIds.mapNotNull { allTitles[it] }
            .filter { !it.hidden && it.id !in hiddenIds }
            .filter { user.canSeeRating(it.content_rating) }
            .sortedBy { it.sort_name?.lowercase() ?: it.name.lowercase() }
            .map { title ->
                val playable = when (title.media_type) {
                    MMMediaType.ALBUM.name -> tracksByTitle[title.id]?.isNotEmpty() == true
                    MMMediaType.BOOK.name -> title.id in digitalTitleIds
                    else -> {
                        val transcodes = transcodesByTitle[title.id] ?: emptyList()
                        transcodes.any { tc ->
                            val fp = tc.file_path ?: return@any false
                            if (TranscoderAgent.needsTranscoding(fp)) {
                                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                            } else true
                        }
                    }
                }
                val progress = progressByTitle[title.id]
                val dur = progress?.duration_seconds
                val progressFraction: Double? = if (progress != null && dur != null && dur > 0) {
                    progress.position_seconds / dur
                } else null

                val card = mutableMapOf<String, Any?>(
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "media_type" to title.media_type,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to title.release_year,
                    "content_rating" to title.content_rating,
                    "playable" to playable,
                    "progress_fraction" to progressFraction
                )
                primaryArtistByTitle[title.id]?.let { card["artist_name"] = it.name }
                primaryAuthorByTitle[title.id]?.let { card["author_name"] = it.name }
                card
            }

        // Tagged tracks (phase B). Track-level tags are a separate
        // surface from title-level ones — a user can tag individual
        // songs with "workout", "focus", etc. without tagging their
        // parent album. We render them as their own list; the client
        // shows them below the title grid with a Play-All affordance.
        // Inheritance: tracks directly tagged AND tracks on tagged albums.
        val taggedTrackIds = TagService.getTrackIdsForTagsWithInheritance(setOf(tagId))
        val tracksById = if (taggedTrackIds.isEmpty()) emptyMap()
            else Track.findAll().filter { it.id in taggedTrackIds }.associateBy { it.id }
        val tagTrackTitleIds = tracksById.values.map { it.title_id }.toSet()
        val tagTrackTitles = Title.findAll()
            .filter { it.id in tagTrackTitleIds }
            .filter { !it.hidden && it.id !in hiddenIds }
            .filter { user.canSeeRating(it.content_rating) }
            .associateBy { it.id }

        val trackRows = tracksById.values
            .filter { it.title_id in tagTrackTitles.keys }
            .sortedWith(compareBy(
                { tagTrackTitles[it.title_id]?.sort_name?.lowercase() ?: "" },
                { it.disc_number },
                { it.track_number }
            ))
            .map { track ->
                val parent = tagTrackTitles[track.title_id]
                mapOf(
                    "track_id" to track.id,
                    "track_name" to track.name,
                    "duration_seconds" to track.duration_seconds,
                    "title_id" to track.title_id,
                    "title_name" to parent?.name,
                    "poster_url" to parent?.posterUrl(PosterSize.THUMBNAIL),
                    "playable" to !track.file_path.isNullOrBlank()
                )
            }

        val result = mapOf(
            "tag" to mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "bg_color" to tag.bg_color,
                "text_color" to tag.textColor()
            ),
            "titles" to titles,
            "total" to titles.size,
            "tracks" to trackRows,
            "track_total" to trackRows.size
        )

        return jsonResponse(gson.toJson(result))
    }

    /** Add a title to a tag (admin only). */
    @Post("/api/v2/catalog/tags/{tagId}/titles/{titleId}")
    fun addTitleToTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("titleId") titleId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Tag.findById(tagId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val exists = TitleTag.findAll().any { it.tag_id == tagId && it.title_id == titleId }
        if (!exists) {
            TitleTag(title_id = titleId, tag_id = tagId).save()
            SearchIndexService.onTitleChanged(titleId)
        }
        return jsonResponse("""{"ok":true}""")
    }

    /** Remove a title from a tag (admin only). */
    @Delete("/api/v2/catalog/tags/{tagId}/titles/{titleId}")
    fun removeTitleFromTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("titleId") titleId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        TitleTag.findAll()
            .filter { it.tag_id == tagId && it.title_id == titleId }
            .forEach { it.delete() }
        SearchIndexService.onTitleChanged(titleId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Search titles for adding to a tag (admin only). */
    @Get("/api/v2/catalog/tags/{tagId}/search-titles")
    fun searchTitlesForTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("q") q: String
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val taggedIds = TagService.getTitleIdsForTags(setOf(tagId))
        val query = q.trim().lowercase()
        if (query.length < 2) return jsonResponse("""{"results":[]}""")

        val results = Title.findAll()
            .filter { !it.hidden && it.id !in taggedIds }
            .filter { user.canSeeRating(it.content_rating) }
            .filter { it.name.lowercase().contains(query) }
            .sortedBy { it.name.lowercase() }
            .take(20)
            .map { mapOf(
                "title_id" to it.id,
                "title_name" to it.name,
                "media_type" to it.media_type,
                "release_year" to it.release_year
            ) }

        return jsonResponse(gson.toJson(mapOf("results" to results)))
    }

    /** Add a track to a tag (admin only). */
    @Post("/api/v2/catalog/tags/{tagId}/tracks/{trackId}")
    fun addTrackToTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Tag.findById(tagId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        Track.findById(trackId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        TagService.addTagToTrack(trackId, tagId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Remove a track from a tag (admin only). */
    @Delete("/api/v2/catalog/tags/{tagId}/tracks/{trackId}")
    fun removeTrackFromTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        TagService.removeTagFromTrack(trackId, tagId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Read the tags currently attached to a track. */
    @Get("/api/v2/catalog/tracks/{trackId}/tags")
    fun listTrackTags(
        ctx: ServiceRequestContext,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        Track.findById(trackId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val tags = TagService.getTagsForTrack(trackId).map { t ->
            mapOf(
                "id" to t.id,
                "name" to t.name,
                "bg_color" to t.bg_color,
                "text_color" to t.textColor()
            )
        }
        return jsonResponse(gson.toJson(mapOf("tags" to tags)))
    }

    /**
     * Replace a track's tag set in one shot (admin only). Mirrors
     * [TitleDetailHttpService.setTags] on the title side; simpler for
     * the web client to diff against its in-hand tag list than to
     * compute individual add/removes.
     */
    @Post("/api/v2/catalog/tracks/{trackId}/tags")
    fun setTrackTags(
        ctx: ServiceRequestContext,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Track.findById(trackId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val tagIds = (body["tag_ids"] as? List<Number>)?.map { it.toLong() }?.toSet() ?: emptySet()

        // Remove anything not in the new set, add anything that's new.
        // This path goes through TagService so SearchIndex notifications
        // fire in both directions.
        val existing = TrackTag.findAll().filter { it.track_id == trackId }
        for (row in existing) {
            if (row.tag_id !in tagIds) TagService.removeTagFromTrack(trackId, row.tag_id)
        }
        val existingIds = existing.map { it.tag_id }.toSet()
        for (tid in tagIds) {
            if (tid !in existingIds) TagService.addTagToTrack(trackId, tid)
        }
        return jsonResponse("""{"ok":true}""")
    }

    /**
     * Admin-only manual override for a track's time signature and/or
     * BPM. Time signature extraction from ID3 frames is unreliable (no
     * standard frame; most taggers don't write it), so a user who
     * knows a song is in 3/4 — important for the "can I waltz to this"
     * ballroom flow — needs a way to set it by hand. Also re-runs
     * auto-tagging so the TIME_SIG / BPM_BUCKET tags update to match.
     *
     * Body is a JSON object; unspecified fields are left unchanged.
     * Pass an explicit null (or empty string for time_signature) to
     * clear a value.
     */
    @Post("/api/v2/catalog/tracks/{trackId}/music-tags")
    fun setTrackMusicTags(
        ctx: ServiceRequestContext,
        @Param("trackId") trackId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        val track = Track.findById(trackId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val body = gson.fromJson(
            ctx.request().aggregate().join().contentUtf8(), Map::class.java
        )

        val changed = mutableListOf<String>()
        if (body.containsKey("time_signature")) {
            val raw = body["time_signature"] as? String
            val normalized = raw?.trim()?.ifBlank { null }
            // Accept only N/M form; reject free-text so "waltz" doesn't
            // end up on the track as a pseudo-time-signature.
            if (normalized != null && !TIME_SIG_PATTERN.matches(normalized)) {
                return badRequest("time_signature must look like '3/4' / '4/4' / '6/8' (or null to clear)")
            }
            track.time_signature = normalized
            changed += "time_signature"
        }
        if (body.containsKey("bpm")) {
            val raw = body["bpm"] as? Number
            val bpm = raw?.toInt()
            if (bpm != null && bpm !in 1..999) {
                return badRequest("bpm must be between 1 and 999 (or null to clear)")
            }
            track.bpm = bpm
            changed += "bpm"
        }
        if (changed.isEmpty()) {
            return badRequest("nothing to update — pass 'time_signature' and/or 'bpm'")
        }
        track.updated_at = java.time.LocalDateTime.now()
        track.save()

        // Re-apply auto-tags so TIME_SIG / BPM_BUCKET tags align with
        // the manual override. Existing genre/style/decade tags stay in
        // place — applyToTrack is additive.
        val year = net.stewart.mediamanager.entity.Title.findById(track.title_id)?.release_year
        TagService_applyRefreshedAutoTags(
            trackId = track.id!!,
            bpm = track.bpm,
            timeSignature = track.time_signature,
            year = year
        )

        return jsonResponse(gson.toJson(mapOf("ok" to true, "changed" to changed)))
    }

    companion object {
        private val TIME_SIG_PATTERN = Regex("""^\d{1,2}/\d{1,2}$""")
    }

    /**
     * Minimal wrapper — re-apply BPM / time signature / decade tags on
     * a track after a manual override. Separate from AutoTagApplicator
     * so we can also strip the now-stale prior BPM_BUCKET / TIME_SIG
     * tag before adding the new one.
     */
    private fun TagService_applyRefreshedAutoTags(
        trackId: Long,
        bpm: Int?,
        timeSignature: String?,
        year: Int?
    ) {
        // Detach any existing BPM_BUCKET / TIME_SIG tag rows so the
        // override doesn't leave stale buckets hanging around.
        val stale = net.stewart.mediamanager.entity.TrackTag.findAll()
            .filter { it.track_id == trackId }
        val staleTagIds = stale.map { it.tag_id }.toSet()
        val purgeableSources = setOf(
            net.stewart.mediamanager.entity.TagSourceType.BPM_BUCKET.name,
            net.stewart.mediamanager.entity.TagSourceType.TIME_SIG.name
        )
        val purgeableTagIds = net.stewart.mediamanager.entity.Tag.findAll()
            .filter { it.id in staleTagIds && it.source_type in purgeableSources }
            .mapNotNull { it.id }
            .toSet()
        stale.filter { it.tag_id in purgeableTagIds }.forEach { it.delete() }

        // Then apply with the fresh values; applyToTrack handles the
        // attach for whichever of BPM/TimeSig/Decade are non-null.
        AutoTagApplicator.applyToTrack(AutoTagApplicator.TrackAutoTagInput(
            trackId = trackId,
            genres = emptyList(),
            styles = emptyList(),
            bpm = bpm,
            timeSignature = timeSignature,
            year = year
        ))
    }

    private fun badRequest(message: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to message)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
