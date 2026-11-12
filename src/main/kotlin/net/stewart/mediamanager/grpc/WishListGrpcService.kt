package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.WishLifecycleStage
import net.stewart.mediamanager.service.WishListService
import net.stewart.mediamanager.service.parseSeriesLine
import java.time.LocalDateTime

/**
 * gRPC surface for the wishlist page (media + transcode + book + album wishes).
 * No image URLs ride on the wire — clients fetch covers via:
 *   - ImageService with IMAGE_TYPE_TMDB_POSTER (TmdbMediaId)              for media wishes
 *   - ImageService with IMAGE_TYPE_OPENLIBRARY_COVER (ol_work_id)         for book wishes
 *   - ImageService with IMAGE_TYPE_CAA_RELEASE_GROUP (release_group_id)   for album wishes
 *   - ImageService with IMAGE_TYPE_POSTER_THUMBNAIL (title_id)            for transcode wishes
 * Web SPA hits the same-origin /tmdb-poster, /proxy/ol, /proxy/caa, /posters
 * servlets directly with the same identifiers.
 */
class WishListGrpcService : WishListServiceGrpcKt.WishListServiceCoroutineImplBase() {

    override suspend fun listWishes(request: Empty): WishListResponse {
        val user = currentUser()
        val userId = user.id!!

        // --- Media wishes ---
        val mediaSummaries = WishListService.getVisibleMediaWishSummariesForUser(userId)
        val media = mediaSummaries.map { summary ->
            val wish = summary.wish
            wishItem {
                id = wish.id!!
                wish.tmdb_id?.let { tmdbId = it }
                mediaType = wish.tmdb_media_type.toProtoMediaType()
                title = wish.tmdb_title ?: ""
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
                dismissible = summary.lifecycleStage == WishLifecycleStage.READY_TO_WATCH
            }
        }

        // --- Transcode wishes ---
        val transcodeRows = WishListService.getActiveTranscodeWishesForUser(userId)
        val titlesById = TitleEntity.findAll().associateBy { it.id }
        val allTranscodes = Transcode.findAll()
        val nasRoot = TranscoderAgent.getNasRoot()
        val transcodes = transcodeRows.mapNotNull { wish ->
            val parent = wish.title_id?.let { titlesById[it] } ?: return@mapNotNull null
            val titleTranscodes = allTranscodes.filter {
                it.title_id == parent.id && it.file_path != null &&
                    TranscoderAgent.needsTranscoding(it.file_path!!)
            }
            val allTranscoded = nasRoot != null && titleTranscodes.isNotEmpty() &&
                titleTranscodes.all { TranscoderAgent.isTranscoded(nasRoot, it.file_path!!) }
            transcodeWishItem {
                id = wish.id!!
                titleId = parent.id!!
                titleName = parent.name
                parent.release_year?.let { year = it }
                this.status = if (allTranscoded) {
                    TranscodeWishStatus.TRANSCODE_WISH_STATUS_READY
                } else {
                    TranscodeWishStatus.TRANSCODE_WISH_STATUS_PENDING
                }
            }
        }

        // --- Book wishes ---
        val bookRows = WishListService.getActiveBookWishesForUser(userId)
        val books = bookRows.mapNotNull { w ->
            val workId = w.open_library_work_id ?: return@mapNotNull null
            bookWishItem {
                id = w.id!!
                olWorkId = workId
                title = w.book_title ?: ""
                w.book_author?.takeIf { it.isNotBlank() }?.let { author = it }
                w.book_series_id?.let { seriesId = it }
                w.book_series_number?.toPlainString()?.let { seriesNumber = it }
            }
        }

        // --- Album wishes ---
        val albumRows = WishListService.getActiveAlbumWishesForUser(userId)
        val albums = albumRows.mapNotNull { w ->
            val rgid = w.musicbrainz_release_group_id ?: return@mapNotNull null
            albumWishItem {
                id = w.id!!
                releaseGroupId = rgid
                title = w.album_title ?: ""
                w.album_primary_artist?.takeIf { it.isNotBlank() }?.let { primaryArtist = it }
                w.album_year?.let { year = it }
                isCompilation = w.album_is_compilation
            }
        }

        // --- has_any_media_wish (any state, not just active) ---
        val hasAnyMedia = WishListItem.findAll().any {
            it.user_id == userId && it.wish_type == WishType.MEDIA.name
        }

        return wishListResponse {
            wishes.addAll(media)
            transcodeWishes.addAll(transcodes)
            bookWishes.addAll(books)
            albumWishes.addAll(albums)
            hasAnyMediaWish = hasAnyMedia
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
        val rows = WishListService.getActiveTranscodeWishesForUser(user.id!!)
        val titlesById = TitleEntity.findAll().associateBy { it.id }
        val allTranscodes = Transcode.findAll()
        val nasRoot = TranscoderAgent.getNasRoot()
        return transcodeWishListResponse {
            wishes.addAll(rows.mapNotNull { wish ->
                val parent = wish.title_id?.let { titlesById[it] } ?: return@mapNotNull null
                val tcs = allTranscodes.filter {
                    it.title_id == parent.id && it.file_path != null &&
                        TranscoderAgent.needsTranscoding(it.file_path!!)
                }
                val allTranscoded = nasRoot != null && tcs.isNotEmpty() &&
                    tcs.all { TranscoderAgent.isTranscoded(nasRoot, it.file_path!!) }
                transcodeWishItem {
                    id = wish.id!!
                    titleId = parent.id!!
                    titleName = parent.name
                    parent.release_year?.let { year = it }
                    this.status = if (allTranscoded) {
                        TranscodeWishStatus.TRANSCODE_WISH_STATUS_READY
                    } else {
                        TranscodeWishStatus.TRANSCODE_WISH_STATUS_PENDING
                    }
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

    override suspend fun addBookWish(request: AddBookWishRequest): AddWishResponse {
        val user = currentUser()
        if (request.olWorkId.isBlank() || request.title.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("ol_work_id and title required"))
        }
        val item = WishListService.addBookWishForUser(
            user.id!!,
            WishListService.BookWishInput(
                openLibraryWorkId = request.olWorkId,
                title = request.title,
                author = if (request.hasAuthor()) request.author else null,
                coverIsbn = if (request.hasCoverIsbn()) request.coverIsbn else null,
                seriesId = if (request.hasSeriesId()) request.seriesId else null,
                seriesNumber = if (request.hasSeriesNumber()) {
                    request.seriesNumber.toBigDecimalOrNull()
                } else null
            )
        )
        return addWishResponse { id = item.id!! }
    }

    override suspend fun removeBookWish(request: RemoveBookWishRequest): Empty {
        val user = currentUser()
        WishListService.removeBookWishForUser(user.id!!, request.olWorkId)
        return Empty.getDefaultInstance()
    }

    override suspend fun addAlbumWish(request: AddAlbumWishRequest): AddWishResponse {
        val user = currentUser()
        if (request.releaseGroupId.isBlank() || request.title.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("release_group_id and title required"))
        }
        val item = WishListService.addAlbumWishForUser(
            user.id!!,
            WishListService.AlbumWishInput(
                musicBrainzReleaseGroupId = request.releaseGroupId,
                title = request.title,
                primaryArtist = if (request.hasPrimaryArtist()) request.primaryArtist else null,
                year = if (request.hasYear()) request.year else null,
                coverReleaseId = if (request.hasCoverReleaseId()) request.coverReleaseId else null,
                isCompilation = request.isCompilation,
            )
        )
        return addWishResponse { id = item.id!! }
    }

    override suspend fun removeAlbumWish(request: RemoveAlbumWishRequest): Empty {
        val user = currentUser()
        WishListService.removeAlbumWishForUser(user.id!!, request.releaseGroupId)
        return Empty.getDefaultInstance()
    }

    override suspend fun wishlistSeriesGaps(
        request: WishlistSeriesGapsRequest
    ): WishlistSeriesGapsResponse {
        val user = currentUser()
        val series = BookSeries.findById(request.seriesId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("series not found"))
        val author = series.author_id?.let { Author.findById(it) }
            ?: return wishlistSeriesGapsResponse {
                added = 0
                alreadyWished = 0
                error = "Series has no associated author; cannot enumerate volumes."
            }
        val olid = author.open_library_author_id
            ?: return wishlistSeriesGapsResponse {
                added = 0
                alreadyWished = 0
                error = "Series author has no Open Library id."
            }

        val ownedWorkIds = TitleEntity.findAll()
            .asSequence()
            .filter { it.media_type == MediaTypeEnum.BOOK.name && it.book_series_id == request.seriesId }
            .mapNotNull { it.open_library_work_id }
            .toSet()

        val alreadyWishedIds = WishListService.activeBookWishWorkIdsForUser(user.id!!)

        val openLibrary = OpenLibraryHttpService()
        val seriesNameLower = series.name.lowercase()

        var added = 0
        var alreadyCount = 0

        openLibrary.listAuthorWorks(olid, limit = 200).asSequence()
            .filter { it.seriesRaw != null }
            .forEach { work ->
                val raw = work.seriesRaw ?: return@forEach
                val (parsedName, number) = parseSeriesLine(raw)
                val matches = parsedName.equals(series.name, ignoreCase = true) ||
                    parsedName.lowercase().contains(seriesNameLower)
                if (!matches) return@forEach
                val workId = work.openLibraryWorkId
                if (workId in ownedWorkIds) return@forEach
                if (workId in alreadyWishedIds) {
                    alreadyCount++
                    return@forEach
                }
                WishListService.addBookWishForUser(
                    user.id!!,
                    WishListService.BookWishInput(
                        openLibraryWorkId = workId,
                        title = work.title,
                        author = author.name,
                        coverIsbn = null,
                        seriesId = series.id,
                        seriesNumber = number,
                    )
                )
                added++
            }

        return wishlistSeriesGapsResponse {
            this.added = added
            this.alreadyWished = alreadyCount
        }
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
                    r.popularity?.let { popularity = it }
                    owned = key in ownedTmdbKeys
                    wished = key in wishedTmdbKeys
                }
            })
        }
    }
}
