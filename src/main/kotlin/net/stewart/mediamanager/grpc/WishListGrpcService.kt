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
import net.stewart.mediamanager.service.WishListService
import java.time.LocalDateTime

class WishListGrpcService : WishListServiceGrpcKt.WishListServiceCoroutineImplBase() {

    override suspend fun listWishes(request: Empty): WishListResponse {
        val user = currentUser()
        val wishes = WishListService.getVisibleMediaWishSummariesForUser(user.id!!)

        return wishListResponse {
            this.wishes.addAll(wishes.map { summary ->
                val wish = summary.wish
                wishItem {
                    id = wish.id!!
                    wish.tmdb_id?.let { tmdbId = it }
                    mediaType = wish.tmdb_media_type.toProtoMediaType()
                    title = wish.tmdb_title ?: ""
                    wish.tmdb_poster_path?.let { posterUrl = "https://image.tmdb.org/t/p/w500$it" }
                    wish.tmdb_release_year?.let { releaseYear = it }
                    wish.season_number?.let { seasonNumber = it }
                    status = when (wish.status) {
                        "ACTIVE" -> WishStatus.WISH_STATUS_ACTIVE
                        "FULFILLED" -> WishStatus.WISH_STATUS_FULFILLED
                        else -> WishStatus.WISH_STATUS_UNKNOWN
                    }
                    voteCount = summary.voteCount
                    userVoted = true
                    voters.addAll(summary.voters)
                    acquisitionStatus = summary.acquisitionStatus.toProtoAcquisitionStatus()
                    lifecycleStage = summary.lifecycleStage.toProtoWishLifecycleStage()
                    summary.titleId?.let { titleId = it }
                    wish.created_at?.let { createdAt = it.toProtoTimestamp() }
                }
            })
        }
    }

    override suspend fun addWish(request: AddWishRequest): AddWishResponse {
        val user = currentUser()
        val mediaType = when (request.mediaType) {
            MediaType.MEDIA_TYPE_MOVIE -> MediaTypeEnum.MOVIE
            MediaType.MEDIA_TYPE_TV -> MediaTypeEnum.TV
            MediaType.MEDIA_TYPE_PERSONAL -> MediaTypeEnum.PERSONAL
            else -> MediaTypeEnum.MOVIE
        }
        val item = WishListService.addMediaWishForUser(
            userId = user.id!!,
            tmdbId = net.stewart.mediamanager.entity.TmdbId(request.tmdbId, mediaType),
            title = request.title,
            posterPath = if (request.hasPosterPath()) request.posterPath else null,
            releaseYear = if (request.hasReleaseYear()) request.releaseYear else null,
            popularity = if (request.hasPopularity()) request.popularity else null,
            seasonNumber = if (request.hasSeasonNumber()) request.seasonNumber else null
        ) ?: throw StatusException(Status.ALREADY_EXISTS.withDescription("Wish already exists"))
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
            // Add a vote (create a new wish for the same tmdb/media/season from this user).
            val existing = WishListItem.findAll().firstOrNull {
                it.user_id == user.id && it.tmdb_id == wish.tmdb_id &&
                    it.tmdb_media_type == wish.tmdb_media_type &&
                    (it.season_number ?: 0) == (wish.season_number ?: 0) &&
                    it.status == "ACTIVE"
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
                    season_number = wish.season_number,
                    wish_type = WishType.MEDIA.name,
                    status = "ACTIVE",
                    created_at = LocalDateTime.now()
                ).save()
            }
        } else {
            // Remove vote (cancel this user's wish for the same tmdb/media/season).
            WishListItem.findAll()
                .filter {
                    it.user_id == user.id && it.tmdb_id == wish.tmdb_id &&
                        it.tmdb_media_type == wish.tmdb_media_type &&
                        (it.season_number ?: 0) == (wish.season_number ?: 0) &&
                        it.status == "ACTIVE"
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
        val user = currentUser()
        val wishes = WishListItem.findAll()
            .filter { it.user_id == user.id && it.wish_type == WishType.TRANSCODE.name && it.status == "ACTIVE" }
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
        val item = WishListService.addTranscodeWishForUser(user.id!!, request.titleId)
            ?: throw StatusException(Status.ALREADY_EXISTS.withDescription("Transcode wish already exists"))
        return addWishResponse { id = item.id!! }
    }

    override suspend fun removeTranscodeWish(request: TitleIdRequest): Empty {
        val user = currentUser()
        WishListService.removeTranscodeWishForUser(user.id!!, request.titleId)
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
