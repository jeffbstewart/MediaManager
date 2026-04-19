package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.Title as TitleEntity
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.service.MusicBrainzHttpService
import net.stewart.mediamanager.service.MusicBrainzService
import net.stewart.mediamanager.service.OpenLibraryHttpService
import net.stewart.mediamanager.service.OpenLibraryService

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
        currentUser()
        val ownedAlbumsByArtist = TitleArtist.findAll().groupingBy { it.artist_id }.eachCount()
        val all = Artist.findAll()
        val filtered = request.q.takeIf { request.hasQ() && it.isNotBlank() }?.lowercase()?.let { needle ->
            all.filter { it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
        } ?: all

        val sorted = when (request.sort.takeIf { request.hasSort() }) {
            "recent" -> filtered.sortedByDescending { it.updated_at ?: it.created_at ?: java.time.LocalDateTime.MIN }
            "popularity" -> filtered.sortedByDescending { ownedAlbumsByArtist[it.id] ?: 0 }
            else -> filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        }

        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return artistListResponse {
            artists.addAll(paged.map { it.toListItem(ownedAlbumsByArtist[it.id] ?: 0) })
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
        val otherWorks = buildArtistOtherWorks(artist, ownedReleaseGroupIds)
        val (members, memberOf) = buildMemberships(artist)

        return artistDetail {
            this.artist = artist.toProto()
            artist.biography?.takeIf { it.isNotBlank() }?.let { biography = it }
            this.ownedAlbums.addAll(ownedAlbums.map { it.toProto() })
            this.otherWorks.addAll(otherWorks)
            this.members.addAll(members)
            this.memberOf.addAll(memberOf)
        }
    }

    private fun buildArtistOtherWorks(
        artist: Artist,
        ownedReleaseGroupIds: Set<String>
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
            m.begin_date?.year?.let { beginYear = it }
            m.end_date?.year?.let { endYear = it }
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
        currentUser()
        val ownedBooksByAuthor = TitleAuthor.findAll().groupingBy { it.author_id }.eachCount()
        val all = Author.findAll()
        val filtered = request.q.takeIf { request.hasQ() && it.isNotBlank() }?.lowercase()?.let { needle ->
            all.filter { it.name.lowercase().contains(needle) || it.sort_name.lowercase().contains(needle) }
        } ?: all

        val sorted = when (request.sort.takeIf { request.hasSort() }) {
            "recent" -> filtered.sortedByDescending { it.updated_at ?: it.created_at ?: java.time.LocalDateTime.MIN }
            "popularity" -> filtered.sortedByDescending { ownedBooksByAuthor[it.id] ?: 0 }
            else -> filtered.sortedBy { it.sort_name.ifBlank { it.name }.lowercase() }
        }

        val (paged, pagination) = paginate(sorted, request.page, request.limit)
        return authorListResponse {
            authors.addAll(paged.map { it.toListItem(ownedBooksByAuthor[it.id] ?: 0) })
            this.pagination = pagination
        }
    }

    override suspend fun getAuthorDetail(request: AuthorIdRequest): AuthorDetail {
        val user = currentUser()
        val author = Author.findById(request.authorId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("author not found"))

        val links = TitleAuthor.findAll().filter { it.author_id == author.id }
        val linkedTitleIds = links.map { it.title_id }.toSet()
        val ownedBooks = TitleEntity.findAll()
            .filter { it.id in linkedTitleIds }
            .filter { it.media_type == MediaTypeEnum.BOOK.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }
            .sortedWith(compareBy(
                { it.first_publication_year ?: it.release_year ?: Int.MAX_VALUE },
                { it.name.lowercase() }
            ))

        val ownedWorkIds = ownedBooks.mapNotNull { it.open_library_work_id }.toSet()
        val otherWorks = buildAuthorOtherWorks(author, ownedWorkIds)

        return authorDetail {
            this.author = author.toProto()
            this.ownedBooks.addAll(ownedBooks.map { it.toProto() })
            this.otherWorks.addAll(otherWorks)
        }
    }

    private fun buildAuthorOtherWorks(
        author: Author,
        ownedWorkIds: Set<String>
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
                }
            }
            .toList()
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
    }
}
