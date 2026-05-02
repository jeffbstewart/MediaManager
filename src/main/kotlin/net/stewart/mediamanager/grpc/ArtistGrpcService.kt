package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import io.grpc.Status
import io.grpc.StatusException
import com.fasterxml.jackson.databind.ObjectMapper
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.RecommendedArtist
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.service.MusicBrainzHttpService
import net.stewart.mediamanager.service.MusicBrainzService
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.OpenLibraryService
import net.stewart.mediamanager.service.RecommendationAgent
import java.time.LocalDateTime

/**
 * Artist + author browse surface for iOS (M2+ / M3+ in the MUSIC / BOOKS plans).
 * Mirrors [net.stewart.mediamanager.armeria.ArtistHttpService] and
 * [net.stewart.mediamanager.armeria.AuthorHttpService] — keep the two in
 * sync when behavior evolves (discography shaping, wish filters, etc.).
 *
 * No URLs in responses: covers and headshots are addressable via ImageService
 * using the identifiers carried on each message.
 */
class ArtistGrpcService(
    private val musicBrainz: MusicBrainzService = MusicBrainzHttpService(),
    private val openLibrary: OpenLibraryService = OpenLibraryHttpService()
) : ArtistServiceGrpcKt.ArtistServiceCoroutineImplBase() {

    // ------------------------------------------------------------------
    // Artists
    // ------------------------------------------------------------------

    override suspend fun listArtists(request: ListArtistsRequest): ArtistListResponse {
        val user = currentUser()
        val ownedAlbumsByArtist = TitleArtist.findAll().groupingBy { it.artist_id }.eachCount()

        // Album-cover fallback: ordered by artist_order so primary credits
        // come first; we want a recognizable cover, not the first guest spot.
        val albumsByArtist: Map<Long, List<Long>> = TitleArtist.findAll()
            .sortedBy { it.artist_order }
            .groupBy({ it.artist_id }, { it.title_id })
        val visibleAlbumIds: Set<Long> = TitleEntity.findAll()
            .filter {
                it.media_type == MediaTypeEnum.ALBUM.name &&
                    !it.hidden &&
                    user.canSeeRating(it.content_rating)
            }
            .mapNotNull { it.id }
            .toSet()

        // Playable-only: artist has at least one album whose tracks
        // include a populated file_path. Computing this once vs. per-row.
        val playableArtistIds: Set<Long> = if (request.playableOnly) {
            val playableTitleIds = net.stewart.mediamanager.entity.Track.findAll()
                .filter { !it.file_path.isNullOrBlank() }
                .map { it.title_id }
                .toSet()
            TitleArtist.findAll()
                .filter { it.title_id in playableTitleIds }
                .map { it.artist_id }
                .toSet()
        } else emptySet()

        val all = Artist.findAll()
        val filtered = all.asSequence()
            .let { seq ->
                if (request.playableOnly) seq.filter { it.id in playableArtistIds } else seq
            }
            .let { seq ->
                val needle = request.q.takeIf { request.hasQ() && it.isNotBlank() }?.lowercase()
                if (needle != null) {
                    seq.filter { it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
                } else seq
            }
            .toList()

        val sortKey = request.sort.takeIf { request.hasSort() }
        val sorted = when (sortKey) {
            "name" -> filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
            "recent" -> filtered.sortedByDescending {
                it.updated_at ?: it.created_at ?: java.time.LocalDateTime.MIN
            }
            // "albums" is the canonical name; "popularity" stays as a
            // back-compat alias for the original ListArtistsRequest shape.
            // No sort key falls through to "albums" so the iOS landing
            // page gets the right default without setting the field.
            "popularity", "albums", null -> filtered.sortedWith(
                compareByDescending<Artist> { ownedAlbumsByArtist[it.id] ?: 0 }
                    .thenBy { it.sort_name.ifBlank { it.name }.lowercase() }
            )
            else -> filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        }

        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return artistListResponse {
            artists.addAll(paged.map { artist ->
                val fallbackAlbumId = albumsByArtist[artist.id]
                    ?.firstOrNull { it in visibleAlbumIds }
                artist.toListItem(
                    ownedAlbumCount = ownedAlbumsByArtist[artist.id] ?: 0,
                    fallbackAlbumTitleId = fallbackAlbumId
                )
            })
            this.pagination = pagination
        }
    }

    override suspend fun getArtistDetail(request: ArtistIdRequest): ArtistDetail {
        val user = currentUser()
        val artist = Artist.findById(request.artistId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("artist not found"))

        val links = TitleArtist.findAll().filter { it.artist_id == artist.id }
        val linkedTitleIds = links.map { it.title_id }.toSet()
        val ownedAlbums = TitleEntity.findAll()
            .filter { it.id in linkedTitleIds }
            .filter { it.media_type == MediaTypeEnum.ALBUM.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }
            .sortedWith(compareBy(
                { it.release_year ?: Int.MAX_VALUE },
                { it.name.lowercase() }
            ))

        val ownedReleaseGroupIds = ownedAlbums.mapNotNull { it.musicbrainz_release_group_id }.toSet()
        val wishedReleaseGroupIds = net.stewart.mediamanager.service.WishListService
            .activeAlbumWishReleaseGroupIdsForUser(user.id!!)
        val otherWorks = buildArtistOtherWorks(artist, ownedReleaseGroupIds, wishedReleaseGroupIds)
        val (members, memberOf) = buildMemberships(artist)

        return artistDetail {
            this.artist = artist.toProto()
            artist.biography?.takeIf { it.isNotBlank() }?.let { biography = it }
            this.ownedAlbums.addAll(ownedAlbums.map { it.toProto(trackCount = it.track_count) })
            this.otherWorks.addAll(otherWorks)
            this.members.addAll(members)
            this.memberOf.addAll(memberOf)
        }
    }

    private fun buildArtistOtherWorks(
        artist: Artist,
        ownedReleaseGroupIds: Set<String>,
        wishedReleaseGroupIds: Set<String>,
    ): List<DiscographyEntry> {
        val mbid = artist.musicbrainz_artist_id ?: return emptyList()
        return musicBrainz.listArtistReleaseGroups(mbid, limit = 100)
            .asSequence()
            .filter { it.musicBrainzReleaseGroupId !in ownedReleaseGroupIds }
            .map { rg ->
                discographyEntry {
                    musicbrainzReleaseGroupId = rg.musicBrainzReleaseGroupId
                    name = rg.title
                    rg.firstReleaseYear?.let { year = it }
                    releaseGroupType = rg.primaryType.toProtoReleaseGroupType()
                    isCompilation = rg.isCompilation
                    secondaryTypes.addAll(rg.secondaryTypes)
                    alreadyWished = rg.musicBrainzReleaseGroupId in wishedReleaseGroupIds
                }
            }
            .toList()
    }

    private fun buildMemberships(artist: Artist): Pair<List<ArtistMemberEntry>, List<ArtistMemberEntry>> {
        val id = artist.id ?: return emptyList<ArtistMemberEntry>() to emptyList()
        val memberships = ArtistMembership.findAll()
            .filter { it.group_artist_id == id || it.member_artist_id == id }
        if (memberships.isEmpty()) return emptyList<ArtistMemberEntry>() to emptyList()

        val otherIds = memberships.flatMap { listOf(it.group_artist_id, it.member_artist_id) }
            .filter { it != id }
            .toSet()
        val others = Artist.findAll().filter { it.id in otherIds }.associateBy { it.id }

        fun toEntry(other: Artist, m: ArtistMembership): ArtistMemberEntry = artistMemberEntry {
            artistId = other.id!!
            name = other.name
            artistType = other.artist_type.toProtoArtistType()
            m.begin_date?.let {
                beginYear = it.year
                beginDate = it.toProtoCalendarDate()
            }
            m.end_date?.let {
                endYear = it.year
                endDate = it.toProtoCalendarDate()
            }
            m.primary_instruments?.takeIf { it.isNotBlank() }?.let { instruments = it }
        }

        val members = memberships.filter { it.group_artist_id == id }
            .sortedByDescending { it.begin_date }
            .mapNotNull { m -> others[m.member_artist_id]?.let { toEntry(it, m) } }
        val memberOf = memberships.filter { it.member_artist_id == id }
            .sortedByDescending { it.begin_date }
            .mapNotNull { m -> others[m.group_artist_id]?.let { toEntry(it, m) } }
        return members to memberOf
    }

    // ------------------------------------------------------------------
    // Authors
    // ------------------------------------------------------------------

    override suspend fun listAuthors(request: ListAuthorsRequest): AuthorListResponse {
        val user = currentUser()
        val ownedBooksByAuthor = TitleAuthor.findAll().groupingBy { it.author_id }.eachCount()

        // Book-cover fallback: ordered by author_order so the primary
        // credit on a co-authored book wins. Mirrors the artist
        // fallback path.
        val booksByAuthor: Map<Long, List<Long>> = TitleAuthor.findAll()
            .sortedBy { it.author_order }
            .groupBy({ it.author_id }, { it.title_id })
        val visibleBookIds: Set<Long> = TitleEntity.findAll()
            .filter {
                it.media_type == MediaTypeEnum.BOOK.name &&
                    !it.hidden &&
                    user.canSeeRating(it.content_rating)
            }
            .mapNotNull { it.id }
            .toSet()

        val all = Author.findAll()
        val filtered = request.q.takeIf { request.hasQ() && it.isNotBlank() }?.lowercase()?.let { needle ->
            all.filter { it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
        } ?: all

        val sorted = when (request.sort) {
            AuthorSort.AUTHOR_SORT_RECENT ->
                filtered.sortedByDescending { it.updated_at ?: it.created_at ?: java.time.LocalDateTime.MIN }
            AuthorSort.AUTHOR_SORT_BOOKS ->
                filtered.sortedByDescending { ownedBooksByAuthor[it.id] ?: 0 }
            // AUTHOR_SORT_NAME (default), AUTHOR_SORT_UNKNOWN, and any
            // future-but-unrecognised value all collapse to name-sort.
            else ->
                filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        }

        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return authorListResponse {
            authors.addAll(paged.map { author ->
                val fallbackBookId = booksByAuthor[author.id]
                    ?.firstOrNull { it in visibleBookIds }
                author.toListItem(
                    ownedBookCount = ownedBooksByAuthor[author.id] ?: 0,
                    fallbackBookTitleId = fallbackBookId,
                )
            })
            this.pagination = pagination
        }
    }

    override suspend fun getAuthorDetail(request: AuthorIdRequest): AuthorDetail {
        val user = currentUser()
        val author = Author.findById(request.authorId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("author not found"))

        val links = TitleAuthor.findAll().filter { it.author_id == author.id }
        val linkedTitleIds = links.map { it.title_id }.toSet()
        val seriesById = net.stewart.mediamanager.entity.BookSeries.findAll().associateBy { it.id }
        val ownedBooks = TitleEntity.findAll()
            .filter { it.id in linkedTitleIds }
            .filter { it.media_type == MediaTypeEnum.BOOK.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }
            .sortedWith(compareBy(
                // Series-first ordering matches the legacy REST handler so
                // the SPA's grouped render doesn't reshuffle on migration.
                { it.book_series_id?.let { id -> seriesById[id]?.name } ?: "zzz" },
                { it.series_number ?: java.math.BigDecimal.ZERO },
                { it.name.lowercase() }
            ))

        val ownedWorkIds = ownedBooks.mapNotNull { it.open_library_work_id }.toSet()
        val wishedWorkIds = net.stewart.mediamanager.service.WishListService
            .activeBookWishWorkIdsForUser(user.id!!)
        val otherWorks = buildAuthorOtherWorks(author, ownedWorkIds, wishedWorkIds)

        return authorDetail {
            this.author = author.toProto()
            this.ownedBooks.addAll(ownedBooks.map { title ->
                val series = title.book_series_id?.let { seriesById[it] }
                title.toProto(
                    seriesName = series?.name,
                    seriesNumber = title.series_number?.toPlainString(),
                )
            })
            this.otherWorks.addAll(otherWorks)
        }
    }

    private fun buildAuthorOtherWorks(
        author: Author,
        ownedWorkIds: Set<String>,
        wishedWorkIds: Set<String>,
    ): List<BibliographyEntry> {
        val olid = author.open_library_author_id ?: return emptyList()
        return openLibrary.listAuthorWorks(olid, limit = 200)
            .asSequence()
            .filter { it.openLibraryWorkId !in ownedWorkIds }
            .map { work ->
                bibliographyEntry {
                    openlibraryWorkId = work.openLibraryWorkId
                    name = work.title
                    work.firstPublishYear?.let { year = it }
                    work.seriesRaw?.let { seriesRaw = it }
                    alreadyWished = work.openLibraryWorkId in wishedWorkIds
                }
            }
            .toList()
    }

    // ------------------------------------------------------------------
    // Recommendations (M8)
    // ------------------------------------------------------------------

    override suspend fun listArtistRecommendations(
        request: ListArtistRecommendationsRequest
    ): ArtistRecommendationsResponse {
        val user = currentUser()
        val userId = user.id!!
        val limit = if (request.limit <= 0) 30 else request.limit.coerceIn(1, 100)

        val rows = RecommendedArtist.findAll()
            .filter { it.user_id == userId && it.dismissed_at == null }
            .sortedByDescending { it.score }
            .take(limit)

        val artistIdsByMbid: Map<String, Long?> = Artist.findAll()
            .associate { (it.musicbrainz_artist_id ?: "") to it.id }
            .filterKeys { it.isNotEmpty() }

        return artistRecommendationsResponse {
            artists.addAll(rows.map { r ->
                artistRecommendation {
                    suggestedArtistMbid = r.suggested_artist_mbid
                    suggestedArtistName = r.suggested_artist_name
                    artistIdsByMbid[r.suggested_artist_mbid]?.let { artistId = it }
                    score = r.score
                    voters.addAll(decodeVoters(r.voters_json))
                    r.representative_release_group_id?.let { representativeReleaseGroupId = it }
                    r.representative_release_title?.let { representativeReleaseTitle = it }
                }
            })
        }
    }

    override suspend fun dismissArtistRecommendation(
        request: DismissArtistRecommendationRequest
    ): Empty {
        val user = currentUser()
        val userId = user.id!!
        val mbid = request.suggestedArtistMbid.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("suggested_artist_mbid required"))

        val row = RecommendedArtist.findAll()
            .firstOrNull { it.user_id == userId && it.suggested_artist_mbid == mbid }
            ?: throw StatusException(Status.NOT_FOUND.withDescription("recommendation not found"))

        row.dismissed_at = LocalDateTime.now()
        row.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun refreshArtistRecommendations(request: Empty): Empty {
        val user = currentUser()
        val userId = user.id!!
        // Fire-and-forget — same shape as the legacy REST handler.
        Thread({
            try { RecommendationAgent.refreshForUserIfAvailable(userId) }
            catch (_: Exception) { /* logged inside the agent */ }
        }, "recommendation-manual-refresh-$userId").apply {
            isDaemon = true
            start()
        }
        return Empty.getDefaultInstance()
    }

    private fun decodeVoters(json: String?): List<RecommendationVoter> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = voterMapper.readValue(json, List::class.java) as List<Map<String, Any?>>
            raw.map { entry ->
                recommendationVoter {
                    mbid = entry["mbid"] as? String ?: ""
                    name = entry["name"] as? String ?: ""
                    albumCount = (entry["album_count"] as? Number)?.toInt() ?: 0
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Pagination helper
    // ------------------------------------------------------------------

    private fun <T> paginate(items: List<T>, pageReq: Int, limitReq: Int): Pair<List<T>, PaginationInfo> {
        val limit = if (limitReq <= 0) DEFAULT_LIMIT else limitReq.coerceIn(1, MAX_LIMIT)
        val page = if (pageReq <= 0) 1 else pageReq
        val total = items.size
        val totalPages = if (total == 0) 1 else ((total + limit - 1) / limit)
        val from = ((page - 1) * limit).coerceAtMost(total)
        val to = (from + limit).coerceAtMost(total)
        val pageItems = items.subList(from, to)
        val info = paginationInfo {
            this.total = total
            this.page = page
            this.limit = limit
            this.totalPages = totalPages
        }
        return pageItems to info
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private val voterMapper = ObjectMapper()
    }
}
