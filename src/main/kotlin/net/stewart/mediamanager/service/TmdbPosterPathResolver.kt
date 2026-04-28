package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.WishListItem

/**
 * Resolves a TMDB poster_path string from a (tmdb_id, media_type) pair by
 * consulting locally-stored data — owned [Title] rows first, then
 * [WishListItem]s, then [TmdbCollectionPart]s. Returns `null` when no
 * local row carries a poster_path for that TMDB key.
 *
 * Shared by [net.stewart.mediamanager.grpc.ImageGrpcService] and the
 * same-origin `/tmdb-poster/{mediaType}/{tmdbId}` HTTP servlet so both
 * paths converge on the same lookup logic. Pure read; no side effects.
 */
object TmdbPosterPathResolver {

    /**
     * @param mediaType "MOVIE" or "TV" (the database column shape).
     */
    fun find(tmdbId: Int, mediaType: String): String? {
        if (tmdbId <= 0) return null

        val title = Title.findAll().firstOrNull {
            it.tmdb_id == tmdbId && it.media_type == mediaType
        }
        if (title?.poster_path != null) return title.poster_path

        val wish = WishListItem.findAll().firstOrNull { it.tmdb_id == tmdbId }
        if (wish?.tmdb_poster_path != null) return wish.tmdb_poster_path

        val collPart = TmdbCollectionPart.findAll()
            .firstOrNull { it.tmdb_movie_id == tmdbId }
        if (collPart != null) {
            val partTitle = Title.findAll().firstOrNull {
                it.tmdb_id == tmdbId && it.media_type == "MOVIE"
            }
            if (partTitle?.poster_path != null) return partTitle.poster_path
        }

        return null
    }
}
