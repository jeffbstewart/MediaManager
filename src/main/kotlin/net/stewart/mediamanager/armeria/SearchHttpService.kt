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
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * Unified search endpoint for the Angular web app. Searches across titles, actors,
 * collections, tags, live TV channels, and cameras. Designed to be adaptable to
 * gRPC for iOS at a later date.
 */
@Blocking
class SearchHttpService {

    private val gson = Gson()

    /**
     * Search across all entity types. Returns categorized results sorted by relevance.
     * Suitable for search-as-you-type (fast, in-memory index for titles).
     */
    @Get("/api/v2/search")
    fun search(
        ctx: ServiceRequestContext,
        @Param("q") query: String,
        @Param("limit") @Default("30") limit: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val results = searchForUser(query, user, limit)
        return jsonResponse(gson.toJson(mapOf("results" to results, "query" to query)))
    }

    /**
     * Core search logic, lifted out of the HTTP method so integration tests
     * can exercise the full entity search matrix against an in-memory H2
     * without needing an Armeria request context. Returns results sorted by
     * descending score and capped at [limit].
     */
    /** Normalize for accent-insensitive, case-insensitive substring matching. */
    private fun foldMatch(s: String): String =
        SearchIndexService.foldAccents(s).lowercase()

    internal fun searchForUser(query: String, user: AppUser, limit: Int = 100): List<Map<String, Any?>> {
        val q = foldMatch(query.trim())
        if (q.length < 2) return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        // Tokens used by the non-title matchers below. Each candidate name
        // / biography is passed through `foldMatch` before comparison so
        // the match is accent-insensitive on both sides.
        val queryTokens = q.split("\\s+".toRegex())

        // --- 1. Titles (movies, TV, personal) via SearchIndexService ---
        val titleIds = SearchIndexService.search(q)
        if (titleIds != null) {
            val allTitles = Title.findAll().associateBy { it.id }
            val nasRoot = TranscoderAgent.getNasRoot()
            val allTranscodes = Transcode.findAll().filter { it.file_path != null }
            val playableTitleIds = allTranscodes
                .filter { tc ->
                    val fp = tc.file_path!!
                    if (TranscoderAgent.needsTranscoding(fp)) {
                        nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                    } else true
                }
                .map { it.title_id }
                .toSet()

            for (titleId in titleIds) {
                val title = allTitles[titleId] ?: continue
                if (title.hidden) continue
                if (!user.canSeeRating(title.content_rating)) continue
                val type = when (title.media_type) {
                    MMMediaType.TV.name -> "tv"
                    MMMediaType.PERSONAL.name -> "personal"
                    MMMediaType.BOOK.name -> "book"
                    MMMediaType.ALBUM.name -> "album"
                    else -> "movie"
                }
                // "Playable" on search results means the video transcode is
                // ready. Books and albums don't use that pipeline, so skip
                // the transcode check for those media types — the library
                // row on the title itself is what gates playback there.
                val playable = when (title.media_type) {
                    MMMediaType.BOOK.name, MMMediaType.ALBUM.name -> true
                    else -> title.id in playableTitleIds
                }
                results.add(mapOf(
                    "type" to type,
                    "title_id" to title.id,
                    "name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "year" to title.release_year,
                    "playable" to playable,
                    "score" to (title.popularity ?: 0.0)
                ))
            }
        }

        // --- 2. Actors (cast members by name) ---
        val seenActors = mutableSetOf<Int>()
        val castMembers = CastMember.findAll()
        for (cm in castMembers) {
            if (cm.tmdb_person_id in seenActors) continue
            val nameLC = cm.name.let(::foldMatch)
            if (queryTokens.all { nameLC.contains(it) }) {
                seenActors.add(cm.tmdb_person_id)
                results.add(mapOf(
                    "type" to "actor",
                    "person_id" to cm.tmdb_person_id,
                    "name" to cm.name,
                    // Gate on profile_path — see note in TitleDetailHttpService.
                    "headshot_url" to if (cm.profile_path != null) "/headshots/${cm.id}" else null,
                    "score" to (cm.popularity ?: 0.0)
                ))
            }
        }

        // --- 2b. Music artists (by name or biography) ---
        // Name matches score higher than biography matches so "Miles Davis"
        // surfaces the artist record ahead of unrelated artists whose bios
        // happen to mention Miles.
        for (a in Artist.findAll()) {
            val nameLC = a.name.let(::foldMatch)
            val nameHit = queryTokens.all { nameLC.contains(it) }
            val bioHit = !nameHit && a.biography?.let(::foldMatch)?.let { bio ->
                queryTokens.all { bio.contains(it) }
            } == true
            if (nameHit || bioHit) {
                results.add(mapOf(
                    "type" to "artist",
                    "artist_id" to a.id,
                    "name" to a.name,
                    "headshot_url" to if (!a.headshot_path.isNullOrBlank() && a.id != null) "/artist-headshots/${a.id}" else null,
                    // Score artists above collections/tags so a search like
                    // "Miles Davis" puts the artist ahead of a tag named
                    // "Miles", but below titles that actually match. Bio-only
                    // matches score lower than name matches.
                    "score" to if (nameHit) 7500.0 else 3500.0
                ))
            }
        }

        // --- 2c. Book authors (by name or biography) — same shape as artists. ---
        for (a in Author.findAll()) {
            val nameLC = a.name.let(::foldMatch)
            val nameHit = queryTokens.all { nameLC.contains(it) }
            val bioHit = !nameHit && a.biography?.let(::foldMatch)?.let { bio ->
                queryTokens.all { bio.contains(it) }
            } == true
            if (nameHit || bioHit) {
                results.add(mapOf(
                    "type" to "author",
                    "author_id" to a.id,
                    "name" to a.name,
                    "headshot_url" to if (!a.headshot_path.isNullOrBlank() && a.id != null) "/author-headshots/${a.id}" else null,
                    "score" to if (nameHit) 7500.0 else 3500.0
                ))
            }
        }

        // --- 2d. Tracks (by song title) ---
        // Resolve to the album title's detail page since there's no per-track
        // surface. Dedup within an album if multiple tracks happen to share
        // a token-identical name (unusual but possible on compilations).
        val allTitlesByIdForTracks = Title.findAll().associateBy { it.id }
        for (track in Track.findAll()) {
            val nameLC = track.name.let(::foldMatch)
            if (queryTokens.all { nameLC.contains(it) }) {
                val album = allTitlesByIdForTracks[track.title_id] ?: continue
                if (!user.canSeeRating(album.content_rating)) continue
                if (album.hidden) continue
                results.add(mapOf(
                    "type" to "track",
                    "track_id" to track.id,
                    "title_id" to album.id,
                    "name" to track.name,
                    "album_name" to album.name,
                    "poster_url" to album.posterUrl(PosterSize.THUMBNAIL),
                    // Tracks sit just above collections/tags but below named
                    // entities — a user typing a well-known song name usually
                    // wants the track first, but if they typed a band name
                    // the artist should still win.
                    "score" to 5500.0
                ))
            }
        }

        // --- 3. Collections ---
        val collections = TmdbCollection.findAll()
        for (col in collections) {
            val nameLC = col.name.let(::foldMatch)
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "collection",
                    "collection_id" to col.id,
                    "name" to col.name,
                    "score" to 8000.0
                ))
            }
        }

        // --- 4. Tags ---
        val tags = TagService.getAllTags()
        val tagCounts = TagService.getTagTitleCounts()
        for (tag in tags) {
            val nameLC = tag.name.let(::foldMatch)
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "tag",
                    "tag_id" to tag.id,
                    "name" to tag.name,
                    "bg_color" to tag.bg_color,
                    "text_color" to tag.textColor(),
                    "title_count" to (tagCounts[tag.id] ?: 0),
                    "score" to 6000.0
                ))
            }
        }

        // --- 5. Live TV channels (by call sign, guide name, or affiliation) ---
        val enabledTunerIds = LiveTvTuner.findAll().filter { it.enabled }.mapNotNull { it.id }.toSet()
        val channels = LiveTvChannel.findAll().filter { it.enabled && it.tuner_id in enabledTunerIds }
        for (ch in channels) {
            val searchable = "${ch.guide_name} ${ch.network_affiliation ?: ""} ${ch.guide_number}".let(::foldMatch)
            if (queryTokens.all { searchable.contains(it) }) {
                results.add(mapOf(
                    "type" to "channel",
                    "channel_id" to ch.id,
                    "name" to "${ch.guide_number} ${ch.guide_name}",
                    "affiliation" to ch.network_affiliation,
                    "score" to 4000.0
                ))
            }
        }

        // --- 6. Cameras ---
        val cameras = Camera.findAll().filter { it.enabled }
        for (cam in cameras) {
            val nameLC = cam.name.let(::foldMatch)
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "camera",
                    "camera_id" to cam.id,
                    "name" to cam.name,
                    "score" to 3000.0
                ))
            }
        }

        // Sort by score descending, limit
        return results.sortedByDescending { (it["score"] as? Number)?.toDouble() ?: 0.0 }.take(limit)
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
