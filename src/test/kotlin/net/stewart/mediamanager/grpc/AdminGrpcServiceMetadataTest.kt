package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEntity
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.BookSeries
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.Track
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.LocalDate
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 10 of [AdminGrpcService] coverage — admin metadata curation:
 * authors, artists, book series, tracks. Mutations + error paths;
 * the pagination helper is shared so happy-path coverage is enough.
 */
class AdminGrpcServiceMetadataTest : GrpcTestBase() {

    @Before
    fun cleanMetaTables() {
        TitleArtist.deleteAll()
        TitleAuthor.deleteAll()
        ArtistMembership.deleteAll()
        Track.deleteAll()
        Artist.deleteAll()
        Author.deleteAll()
        BookSeries.deleteAll()
    }

    // ---------------------- listAdminAuthors ----------------------

    @Test
    fun `listAdminAuthors returns authors sorted by sort_name with q filter`() = runBlocking {
        val admin = createAdminUser(username = "auth-list")
        Author(name = "Isaac Asimov", sort_name = "Asimov, Isaac").save()
        Author(name = "Ursula K. Le Guin", sort_name = "Le Guin, Ursula K.").save()
        Author(name = "Octavia Butler", sort_name = "Butler, Octavia").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val all = stub.listAdminAuthors(listAdminAuthorsRequest { })
            assertEquals(listOf("Isaac Asimov", "Octavia Butler", "Ursula K. Le Guin"),
                all.authorsList.map { it.name })

            val filtered = stub.listAdminAuthors(listAdminAuthorsRequest { q = "asimov" })
            assertEquals(1, filtered.authorsCount)
            assertEquals("Isaac Asimov", filtered.authorsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateAuthor ----------------------

    @Test
    fun `updateAuthor mutates name, sort_name, biography in place`() = runBlocking {
        val admin = createAdminUser(username = "auth-update")
        val a = Author(name = "Old", sort_name = "Old").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateAuthor(updateAuthorRequest {
                authorId = a.id!!
                name = "New"
                sortName = "New, Author"
                biography = "Bio text"
            })
            val refreshed = Author.findById(a.id!!)!!
            assertEquals("New", refreshed.name)
            assertEquals("New, Author", refreshed.sort_name)
            assertEquals("Bio text", refreshed.biography)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateAuthor returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "auth-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateAuthor(updateAuthorRequest {
                    authorId = 999_999
                    name = "x"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deleteAuthor ----------------------

    @Test
    fun `deleteAuthor cascades through TitleAuthor links`() = runBlocking {
        val admin = createAdminUser(username = "auth-delete")
        val a = Author(name = "Doomed", sort_name = "Doomed").apply { save() }
        val title = createTitle(name = "Book", mediaType = MediaTypeEntity.BOOK.name)
        TitleAuthor(title_id = title.id!!, author_id = a.id!!,
            author_order = 0).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteAuthor(adminAuthorIdRequest { authorId = a.id!! })
            assertNull(Author.findById(a.id!!))
            assertEquals(0, TitleAuthor.findAll().count { it.author_id == a.id })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteAuthor returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "auth-delete-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteAuthor(adminAuthorIdRequest { authorId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- mergeAuthors ----------------------

    @Test
    fun `mergeAuthors re-points titles from drop to keep, dedupes overlap, deletes drop`() = runBlocking {
        val admin = createAdminUser(username = "auth-merge")
        val keep = Author(name = "Keep", sort_name = "Keep").apply { save() }
        val drop = Author(name = "Drop", sort_name = "Drop").apply { save() }

        val sharedTitle = createTitle(name = "Shared",
            mediaType = MediaTypeEntity.BOOK.name)
        val dropOnly = createTitle(name = "DropOnly",
            mediaType = MediaTypeEntity.BOOK.name)
        // Both authors linked to the shared title; only drop is linked to dropOnly.
        TitleAuthor(title_id = sharedTitle.id!!, author_id = keep.id!!,
            author_order = 0).save()
        TitleAuthor(title_id = sharedTitle.id!!, author_id = drop.id!!,
            author_order = 0).save()
        TitleAuthor(title_id = dropOnly.id!!, author_id = drop.id!!,
            author_order = 0).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.mergeAuthors(mergeAuthorsRequest {
                keepAuthorId = keep.id!!
                dropAuthorId = drop.id!!
            })
            // Drop is gone.
            assertNull(Author.findById(drop.id!!))
            // sharedTitle: still one link to keep (the duplicate from drop was deleted).
            assertEquals(1, TitleAuthor.findAll().count {
                it.title_id == sharedTitle.id && it.author_id == keep.id
            })
            // dropOnly: re-pointed to keep.
            assertEquals(1, TitleAuthor.findAll().count {
                it.title_id == dropOnly.id && it.author_id == keep.id
            })
            // No remaining drop-author links.
            assertEquals(0, TitleAuthor.findAll().count { it.author_id == drop.id })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mergeAuthors rejects keep == drop with INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "auth-merge-same")
        val a = Author(name = "Same", sort_name = "Same").apply { save() }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.mergeAuthors(mergeAuthorsRequest {
                    keepAuthorId = a.id!!
                    dropAuthorId = a.id!!
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mergeAuthors returns NOT_FOUND for unknown keep or drop`() = runBlocking {
        val admin = createAdminUser(username = "auth-merge-404")
        val a = Author(name = "X", sort_name = "X").apply { save() }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val unknownKeep = assertFailsWith<StatusException> {
                stub.mergeAuthors(mergeAuthorsRequest {
                    keepAuthorId = 999_999
                    dropAuthorId = a.id!!
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownKeep.status.code)
            val unknownDrop = assertFailsWith<StatusException> {
                stub.mergeAuthors(mergeAuthorsRequest {
                    keepAuthorId = a.id!!
                    dropAuthorId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownDrop.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listAdminArtists ----------------------

    @Test
    fun `listAdminArtists returns artists sorted with q filter`() = runBlocking {
        val admin = createAdminUser(username = "art-list")
        Artist(name = "Bowie", sort_name = "Bowie",
            artist_type = ArtistTypeEntity.PERSON.name).save()
        Artist(name = "The Beatles", sort_name = "Beatles, The",
            artist_type = ArtistTypeEntity.GROUP.name).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listAdminArtists(listAdminArtistsRequest { q = "beatles" })
            assertEquals(1, resp.artistsCount)
            assertEquals("The Beatles", resp.artistsList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateArtist ----------------------

    @Test
    fun `updateArtist mutates name, sort_name, type, biography`() = runBlocking {
        val admin = createAdminUser(username = "art-update")
        val a = Artist(name = "Old", sort_name = "Old",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateArtist(updateArtistRequest {
                artistId = a.id!!
                name = "New"
                sortName = "New, Artist"
                artistType = ArtistType.ARTIST_TYPE_PERSON
                biography = "Bio"
            })
            val refreshed = Artist.findById(a.id!!)!!
            assertEquals("New", refreshed.name)
            assertEquals(ArtistTypeEntity.PERSON.name, refreshed.artist_type)
            assertEquals("Bio", refreshed.biography)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateArtist NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "art-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateArtist(updateArtistRequest {
                    artistId = 999_999
                    name = "x"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateArtist maps every ArtistType proto value to the matching entity`() = runBlocking {
        val admin = createAdminUser(username = "art-update-types")
        val a = Artist(name = "Type Walker", sort_name = "Type Walker",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }

        val cases = listOf(
            ArtistType.ARTIST_TYPE_GROUP to ArtistTypeEntity.GROUP,
            ArtistType.ARTIST_TYPE_ORCHESTRA to ArtistTypeEntity.ORCHESTRA,
            ArtistType.ARTIST_TYPE_CHOIR to ArtistTypeEntity.CHOIR,
            ArtistType.ARTIST_TYPE_OTHER to ArtistTypeEntity.OTHER,
        )
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            for ((proto, entity) in cases) {
                stub.updateArtist(updateArtistRequest {
                    artistId = a.id!!
                    artistType = proto
                })
                assertEquals(entity.name,
                    Artist.findById(a.id!!)!!.artist_type,
                    "$proto must map to entity $entity")
            }
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deleteArtist ----------------------

    @Test
    fun `deleteArtist cascades through TitleArtist and ArtistMembership`() = runBlocking {
        val admin = createAdminUser(username = "art-delete")
        val a = Artist(name = "Doomed", sort_name = "Doomed",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val other = Artist(name = "Other", sort_name = "Other",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = a.id!!,
            artist_order = 0).save()
        ArtistMembership(group_artist_id = other.id!!,
            member_artist_id = a.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteArtist(adminArtistIdRequest { artistId = a.id!! })
            assertNull(Artist.findById(a.id!!))
            assertEquals(0, TitleArtist.findAll().count { it.artist_id == a.id })
            assertEquals(0, ArtistMembership.findAll().count {
                it.group_artist_id == a.id || it.member_artist_id == a.id
            })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteArtist returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "art-delete-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteArtist(adminArtistIdRequest { artistId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- mergeArtists ----------------------

    @Test
    fun `mergeArtists re-points TitleArtist rows and deletes drop`() = runBlocking {
        val admin = createAdminUser(username = "art-merge")
        val keep = Artist(name = "Keep", sort_name = "Keep",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val drop = Artist(name = "Drop", sort_name = "Drop",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = album.id!!, artist_id = drop.id!!,
            artist_order = 0).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.mergeArtists(mergeArtistsRequest {
                keepArtistId = keep.id!!
                dropArtistId = drop.id!!
            })
            assertNull(Artist.findById(drop.id!!))
            assertEquals(1, TitleArtist.findAll().count {
                it.title_id == album.id && it.artist_id == keep.id
            })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mergeArtists rejects keep == drop`() = runBlocking {
        val admin = createAdminUser(username = "art-merge-same")
        val a = Artist(name = "A", sort_name = "A",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.mergeArtists(mergeArtistsRequest {
                    keepArtistId = a.id!!
                    dropArtistId = a.id!!
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mergeArtists returns NOT_FOUND when keep or drop is missing`() = runBlocking {
        val admin = createAdminUser(username = "art-merge-404")
        val real = Artist(name = "Real", sort_name = "Real",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val noKeep = assertFailsWith<StatusException> {
                stub.mergeArtists(mergeArtistsRequest {
                    keepArtistId = 999_999
                    dropArtistId = real.id!!
                })
            }
            assertEquals(Status.Code.NOT_FOUND, noKeep.status.code)

            val noDrop = assertFailsWith<StatusException> {
                stub.mergeArtists(mergeArtistsRequest {
                    keepArtistId = real.id!!
                    dropArtistId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, noDrop.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `mergeArtists deletes redundant TitleArtist links and re-points memberships`() = runBlocking {
        val admin = createAdminUser(username = "art-merge-membership")
        val keep = Artist(name = "Keeper", sort_name = "Keeper",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        val drop = Artist(name = "Dropper", sort_name = "Dropper",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        // Album already credits BOTH keep and drop — drop's row must be
        // deleted (not re-pointed) because keep already covers that title.
        val sharedAlbum = createTitle(name = "Shared",
            mediaType = MediaTypeEntity.ALBUM.name)
        TitleArtist(title_id = sharedAlbum.id!!, artist_id = keep.id!!,
            artist_order = 0).save()
        TitleArtist(title_id = sharedAlbum.id!!, artist_id = drop.id!!,
            artist_order = 1).save()

        // Membership where drop appears as a group → must re-point to keep.
        val member = Artist(name = "Member", sort_name = "Member",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val rePointed = ArtistMembership(
            group_artist_id = drop.id!!, member_artist_id = member.id!!,
            begin_date = LocalDate.of(2010, 1, 1)).apply { save() }
        // Membership where drop is BOTH sides → after rewrite collapses
        // to keep↔keep, which is a self-loop and gets deleted.
        val selfLoop = ArtistMembership(
            group_artist_id = drop.id!!, member_artist_id = drop.id!!,
            begin_date = LocalDate.of(2011, 1, 1)).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.mergeArtists(mergeArtistsRequest {
                keepArtistId = keep.id!!
                dropArtistId = drop.id!!
            })
            assertNull(Artist.findById(drop.id!!))
            // Only one TitleArtist row remains for the shared album, on keep.
            val remainingLinks = TitleArtist.findAll()
                .filter { it.title_id == sharedAlbum.id }
            assertEquals(1, remainingLinks.size)
            assertEquals(keep.id!!, remainingLinks.single().artist_id)
            // Re-pointed membership now references keep on the group side.
            val refreshed = ArtistMembership.findById(rePointed.id!!)!!
            assertEquals(keep.id!!, refreshed.group_artist_id)
            // Self-loop dropped.
            assertNull(ArtistMembership.findById(selfLoop.id!!))
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- book series ----------------------

    @Test
    fun `listBookSeries returns volume_count from BOOK titles`() = runBlocking {
        val admin = createAdminUser(username = "bs-list")
        val author = Author(name = "Asimov", sort_name = "Asimov").apply { save() }
        val series = BookSeries(name = "Foundation",
            author_id = author.id).apply { save() }
        // Two book volumes in this series.
        val v1 = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id; save()
        }
        val v2 = createTitle(name = "Foundation and Empire",
            mediaType = MediaTypeEntity.BOOK.name).apply {
            book_series_id = series.id; save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listBookSeries(listBookSeriesRequest { })
            assertEquals(1, resp.seriesCount)
            assertEquals("Foundation", resp.seriesList.single().name)
            assertEquals(2, resp.seriesList.single().volumeCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateBookSeries mutates name and primary_author_id`() = runBlocking {
        val admin = createAdminUser(username = "bs-update")
        val series = BookSeries(name = "Old Name").apply { save() }
        val author = Author(name = "A", sort_name = "A").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateBookSeries(updateBookSeriesRequest {
                seriesId = series.id!!
                name = "New Name"
                primaryAuthorId = author.id!!
            })
            val refreshed = BookSeries.findById(series.id!!)!!
            assertEquals("New Name", refreshed.name)
            assertEquals(author.id, refreshed.author_id)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reassignTitleToSeries mutates book_series_id and series_number`() = runBlocking {
        val admin = createAdminUser(username = "bs-reassign")
        val title = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name)
        val series = BookSeries(name = "S").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.reassignTitleToSeries(reassignTitleToSeriesRequest {
                titleId = title.id!!
                seriesId = series.id!!
                seriesNumber = "1.5"
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(series.id, refreshed.book_series_id)
            assertTrue(refreshed.series_number != null)
            assertEquals(0, refreshed.series_number!!.compareTo(java.math.BigDecimal("1.5")))
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reassignTitleToSeries refuses non-book titles with FAILED_PRECONDITION`() = runBlocking {
        val admin = createAdminUser(username = "bs-reassign-notbook")
        val movie = createTitle(name = "Movie",
            mediaType = MediaTypeEntity.MOVIE.name)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.reassignTitleToSeries(reassignTitleToSeriesRequest {
                    titleId = movie.id!!
                })
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reassignTitleToSeries rejects non-decimal series_number`() = runBlocking {
        val admin = createAdminUser(username = "bs-reassign-baddec")
        val book = createTitle(name = "Book",
            mediaType = MediaTypeEntity.BOOK.name)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.reassignTitleToSeries(reassignTitleToSeriesRequest {
                    titleId = book.id!!
                    seriesNumber = "not-a-decimal"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- track edits ----------------------

    @Test
    fun `updateTrack mutates name, track_number, disc_number`() = runBlocking {
        val admin = createAdminUser(username = "tk-update")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "Old").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateTrack(updateTrackRequest {
                trackId = track.id!!
                name = "New"
                trackNumber = 5
                discNumber = 2
            })
            val refreshed = Track.findById(track.id!!)!!
            assertEquals("New", refreshed.name)
            assertEquals(5, refreshed.track_number)
            assertEquals(2, refreshed.disc_number)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reorderTracks rejects an album with no tracks and rejects mismatched track ids`() = runBlocking {
        val admin = createAdminUser(username = "tk-reorder")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val otherAlbum = createTitle(name = "Other",
            mediaType = MediaTypeEntity.ALBUM.name)
        val tA = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "A").apply { save() }
        // A track on a different album — should be rejected if submitted.
        val tForeign = Track(title_id = otherAlbum.id!!, track_number = 1,
            disc_number = 1, name = "Foreign").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)

            // Album with no tracks -> NOT_FOUND.
            val emptyAlbum = createTitle(name = "Empty",
                mediaType = MediaTypeEntity.ALBUM.name)
            val emptyEx = assertFailsWith<StatusException> {
                stub.reorderTracks(reorderTracksRequest {
                    albumTitleId = emptyAlbum.id!!
                    order.add(trackOrderEntry {
                        trackId = 1; discNumber = 1; trackNumber = 1
                    })
                })
            }
            assertEquals(Status.Code.NOT_FOUND, emptyEx.status.code)

            // Mismatched track id (track belongs to other album) -> INVALID_ARGUMENT.
            val mismatchEx = assertFailsWith<StatusException> {
                stub.reorderTracks(reorderTracksRequest {
                    albumTitleId = album.id!!
                    order.add(trackOrderEntry {
                        trackId = tForeign.id!!; discNumber = 1; trackNumber = 1
                    })
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, mismatchEx.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reorderTracks rewrites disc_number and track_number for valid entries`() = runBlocking {
        // Note: the reorder loop saves tracks sequentially. The
        // (title_id, disc_number, track_number) UNIQUE constraint means
        // a naive swap of two tracks at the same disc would collide
        // mid-save. We avoid the collision by moving both tracks to
        // brand-new (disc, track) coordinates that don't overlap with
        // the originals.
        val admin = createAdminUser(username = "tk-reorder-ok")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val tA = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "A").apply { save() }
        val tB = Track(title_id = album.id!!, track_number = 2,
            disc_number = 1, name = "B").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.reorderTracks(reorderTracksRequest {
                albumTitleId = album.id!!
                order.add(trackOrderEntry {
                    trackId = tB.id!!; discNumber = 2; trackNumber = 1
                })
                order.add(trackOrderEntry {
                    trackId = tA.id!!; discNumber = 2; trackNumber = 2
                })
            })
            assertEquals(2, Track.findById(tB.id!!)!!.disc_number)
            assertEquals(1, Track.findById(tB.id!!)!!.track_number)
            assertEquals(2, Track.findById(tA.id!!)!!.disc_number)
            assertEquals(2, Track.findById(tA.id!!)!!.track_number)
        } finally {
            authed.shutdownNow()
        }
    }
}
