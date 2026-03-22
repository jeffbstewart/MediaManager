package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.service.TmdbService
import java.time.LocalDateTime

class WishListGrpcService : WishListServiceGrpcKt.WishListServiceCoroutineImplBase() {

    override suspend fun listWishes(request: Empty): WishListResponse {
        val user = currentUser()
        val wishes = WishListItem.findAll()
            .filter { it.wish_type == WishType.MEDIA.name && it.status != "CANCELLED" }

        val grouped = wishes.groupBy { "${it.tmdb_id}:${it.tmdb_media_type}" }

        return wishListResponse {
            this.wishes.addAll(grouped.values.mapNotNull { items ->
                val first = items.first()
                val userVoted = items.any { it.user_id == user.id }
                wishItem {
                    id = first.id!!
                    first.tmdb_id?.let { tmdbId = it }
                    mediaType = first.tmdb_media_type.toProtoMediaType()
                    title = first.tmdb_title ?: ""
                    first.tmdb_poster_path?.let { posterUrl = "https://image.tmdb.org/t/p/w500$it" }
                    first.tmdb_release_year?.let { releaseYear = it }
                    first.season_number?.let { seasonNumber = it }
                    status = when (first.status) {
                        "ACTIVE" -> WishStatus.WISH_STATUS_ACTIVE
                        "FULFILLED" -> WishStatus.WISH_STATUS_FULFILLED
                        else -> WishStatus.WISH_STATUS_UNKNOWN
                    }
                    voteCount = items.size
                    this.userVoted = userVoted
                    voters.addAll(items.mapNotNull { wish ->
                        AppUser.findById(wish.user_id)?.username
                    })
                    first.created_at?.let { createdAt = it.toProtoTimestamp() }
                }
            })
        }
    }

    override suspend fun addWish(request: AddWishRequest): AddWishResponse {
        val user = currentUser()
        val item = WishListItem(
            user_id = user.id!!,
            tmdb_id = request.tmdbId,
            tmdb_media_type = when (request.mediaType) {
                MediaType.MEDIA_TYPE_MOVIE -> MediaTypeEnum.MOVIE.name
                MediaType.MEDIA_TYPE_TV -> MediaTypeEnum.TV.name
                else -> MediaTypeEnum.MOVIE.name
            },
            tmdb_title = request.title,
            tmdb_poster_path = if (request.hasPosterPath()) request.posterPath else null,
            tmdb_release_year = if (request.hasReleaseYear()) request.releaseYear else null,
            tmdb_popularity = if (request.hasPopularity()) request.popularity else null,
            season_number = if (request.hasSeasonNumber()) request.seasonNumber else null,
            wish_type = WishType.MEDIA.name,
            status = "ACTIVE",
            created_at = LocalDateTime.now()
        )
        item.save()
        return addWishResponse { id = item.id!! }
    }

    override suspend fun cancelWish(request: WishIdRequest): Empty {
        val user = currentUser()
        val wish = WishListItem.findById(request.wishId)
        if (wish != null && wish.user_id == user.id!!) {
            wish.status = "CANCELLED"
            wish.save()
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun voteOnWish(request: VoteRequest): Empty {
        val user = currentUser()
        val wish = WishListItem.findById(request.wishId) ?: return Empty.getDefaultInstance()

        if (request.vote) {
            // Add a vote (create a new wish for the same tmdb_id/media_type from this user)
            val existing = WishListItem.findAll().firstOrNull {
                it.user_id == user.id && it.tmdb_id == wish.tmdb_id &&
                    it.tmdb_media_type == wish.tmdb_media_type && it.status == "ACTIVE"
            }
            if (existing == null) {
                WishListItem(
                    user_id = user.id!!,
                    tmdb_id = wish.tmdb_id,
                    tmdb_media_type = wish.tmdb_media_type,
                    tmdb_title = wish.tmdb_title,
                    tmdb_poster_path = wish.tmdb_poster_path,
                    tmdb_release_year = wish.tmdb_release_year,
                    tmdb_popularity = wish.tmdb_popularity,
                    wish_type = WishType.MEDIA.name,
                    status = "ACTIVE",
                    created_at = LocalDateTime.now()
                ).save()
            }
        } else {
            // Remove vote (cancel this user's wish for the same tmdb_id/media_type)
            WishListItem.findAll()
                .filter {
                    it.user_id == user.id && it.tmdb_id == wish.tmdb_id &&
                        it.tmdb_media_type == wish.tmdb_media_type && it.status == "ACTIVE"
                }
                .forEach { it.status = "CANCELLED"; it.save() }
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun dismissWish(request: WishIdRequest): Empty {
        val user = currentUser()
        val wish = WishListItem.findById(request.wishId)
        if (wish != null && wish.user_id == user.id!!) {
            wish.status = "DISMISSED"
            wish.save()
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun listTranscodeWishes(request: Empty): TranscodeWishListResponse {
        val wishes = WishListItem.findAll()
            .filter { it.wish_type == WishType.TRANSCODE.name && it.status == "ACTIVE" }
        val titleIds = wishes.mapNotNull { it.title_id }.toSet()
        val titles = TitleEntity.findAll().filter { it.id in titleIds }.associateBy { it.id }

        return transcodeWishListResponse {
            this.wishes.addAll(wishes.mapNotNull { wish ->
                val title = wish.title_id?.let { titles[it] } ?: return@mapNotNull null
                transcodeWishItem {
                    titleId = title.id!!
                    titleName = title.name
                    title.posterUrl(PosterSize.FULL)?.let { posterUrl = it }
                    title.release_year?.let { year = it }
                }
            })
        }
    }

    override suspend fun addTranscodeWish(request: TitleIdRequest): AddWishResponse {
        val user = currentUser()
        val item = WishListItem(
            user_id = user.id!!,
            title_id = request.titleId,
            wish_type = WishType.TRANSCODE.name,
            status = "ACTIVE",
            created_at = LocalDateTime.now()
        )
        item.save()
        return addWishResponse { id = item.id!! }
    }

    override suspend fun removeTranscodeWish(request: TitleIdRequest): Empty {
        val user = currentUser()
        WishListItem.findAll()
            .filter { it.user_id == user.id && it.title_id == request.titleId && it.wish_type == WishType.TRANSCODE.name }
            .forEach { it.delete() }
        return Empty.getDefaultInstance()
    }

    override suspend fun searchTmdb(request: TmdbSearchRequest): TmdbSearchResponse {
        val query = request.query.trim()
        if (query.isBlank()) return TmdbSearchResponse.getDefaultInstance()

        val tmdbService = TmdbService()
        val results = when (request.type) {
            MediaType.MEDIA_TYPE_MOVIE -> tmdbService.searchMovieMultiple(query)
            MediaType.MEDIA_TYPE_TV -> tmdbService.searchTvMultiple(query)
            else -> tmdbService.searchMovieMultiple(query) + tmdbService.searchTvMultiple(query)
        }

        val ownedTmdbKeys = TitleEntity.findAll()
            .filter { it.tmdb_id != null }
            .map { "${it.tmdb_id}:${it.media_type}" }
            .toSet()
        val wishedTmdbKeys = WishListItem.findAll()
            .filter { it.wish_type == WishType.MEDIA.name && it.status == "ACTIVE" }
            .map { "${it.tmdb_id}:${it.tmdb_media_type}" }
            .toSet()

        return tmdbSearchResponse {
            this.results.addAll(results.filter { it.found }.map { r ->
                val key = "${r.tmdbId}:${r.mediaType}"
                tmdbResult {
                    r.tmdbId?.let { tmdbId = it }
                    title = r.title ?: ""
                    mediaType = r.mediaType.toProtoMediaType()
                    r.releaseYear?.let { releaseYear = it }
                    r.posterPath?.let { posterUrl = "https://image.tmdb.org/t/p/w500$it" }
                    r.popularity?.let { popularity = it }
                    owned = key in ownedTmdbKeys
                    wished = key in wishedTmdbKeys
                }
            })
        }
    }
}
