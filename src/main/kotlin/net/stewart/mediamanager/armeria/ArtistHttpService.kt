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
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist

/**
 * Artist browse surface. Mirrors [AuthorHttpService] but reads from the
 * MusicBrainz-sourced `artist` table and the `title_artist` link. "Other
 * Works" (MB bibliography for the artist) is a later milestone (M3) — this
 * M2 version shows bio, headshot, begin/end dates, and the owned albums.
 */
@Blocking
class ArtistHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/artists")
    fun list(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        // Owned-album count per artist (lead with most-collected).
        val byArtist = TitleArtist.findAll().groupingBy { it.artist_id }.eachCount()
        val artists = Artist.findAll()
            .sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
            .map { artist ->
                mapOf(
                    "id" to artist.id,
                    "name" to artist.name,
                    "sort_name" to artist.sort_name,
                    "artist_type" to artist.artist_type,
                    "headshot_url" to headshotUrl(artist),
                    "album_count" to (byArtist[artist.id] ?: 0)
                )
            }

        return jsonResponse(gson.toJson(mapOf("artists" to artists)))
    }

    @Get("/api/v2/catalog/artists/{artistId}")
    fun detail(
        ctx: ServiceRequestContext,
        @Param("artistId") artistId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val artist = Artist.findById(artistId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val artistLinks = TitleArtist.findAll().filter { it.artist_id == artistId }
        val linkedTitleIds = artistLinks.map { it.title_id }.toSet()
        val titles = Title.findAll()
            .filter { it.id in linkedTitleIds }
            .filter { it.media_type == MMMediaType.ALBUM.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }

        val ownedAlbums = titles.map { title ->
            mapOf(
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "release_year" to title.release_year,
                "track_count" to title.track_count
            )
        }.sortedWith(compareBy(
            // Release year ascending puts the debut album first, then chronological.
            // Falls back to name for albums with no year on record.
            { (it["release_year"] as? Int) ?: Int.MAX_VALUE },
            { (it["title_name"] as? String)?.lowercase() ?: "" }
        ))

        val result = mapOf(
            "id" to artist.id,
            "name" to artist.name,
            "sort_name" to artist.sort_name,
            "artist_type" to artist.artist_type,
            "biography" to artist.biography,
            "headshot_url" to headshotUrl(artist),
            "begin_date" to artist.begin_date?.toString(),
            "end_date" to artist.end_date?.toString(),
            "musicbrainz_artist_id" to artist.musicbrainz_artist_id,
            "owned_albums" to ownedAlbums
        )
        return jsonResponse(gson.toJson(result))
    }

    /**
     * Headshot URL mirrors the author pattern. Wikimedia / Wikipedia-sourced
     * headshots route through [net.stewart.mediamanager.service.AuthorHeadshotCacheService]
     * (reused here — same download + SSRF-screened cache); served at
     * `/author-headshots/{id}` for authors and `/artist-headshots/{id}` for
     * artists. M2 returns null when no headshot has been fetched yet; the
     * [net.stewart.mediamanager.service.ArtistEnrichmentAgent] populates it
     * in the background.
     */
    private fun headshotUrl(artist: Artist): String? = when {
        !artist.headshot_path.isNullOrBlank() && artist.id != null -> "/artist-headshots/${artist.id}"
        else -> null
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
