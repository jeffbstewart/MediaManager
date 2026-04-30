package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 12 of [AdminGrpcService] coverage — unmatched book + audio
 * triage RPCs (the non-HTTP-bound ones), plus searchCatalogTitles
 * and searchCatalogTracks.
 *
 * The OpenLibrary / MusicBrainz network paths (linkUnmatchedBookByIsbn,
 * searchOpenLibrary, searchMusicBrainzForUnmatchedAudio) are not
 * tested here — they require HTTP fixtures.
 */
class AdminGrpcServiceUnmatchedBookAudioTest : GrpcTestBase() {

    @Before
    fun cleanUnmatchedTables() {
        UnmatchedAudio.deleteAll()
        UnmatchedBook.deleteAll()
        Track.deleteAll()
    }

    // ---------------------- unmatched books ----------------------

    @Test
    fun `listUnmatchedBooks returns only UNMATCHED rows newest-first`() = runBlocking {
        val admin = createAdminUser(username = "ub-list")
        UnmatchedBook(file_path = "/older.epub", file_name = "older.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now().minusHours(2)).save()
        UnmatchedBook(file_path = "/newer.epub", file_name = "newer.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).save()
        UnmatchedBook(file_path = "/linked.epub", file_name = "linked.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.LINKED.name,
            discovered_at = LocalDateTime.now().minusHours(1)).save()
        UnmatchedBook(file_path = "/ignored.epub", file_name = "ignored.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.IGNORED.name,
            discovered_at = LocalDateTime.now().minusMinutes(30)).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUnmatchedBooks(Empty.getDefaultInstance())
            assertEquals(2, resp.itemsCount,
                "LINKED + IGNORED rows excluded")
            // Ordered by discovered_at desc — "newer" first.
            assertEquals("newer.epub", resp.itemsList.first().fileName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `ignoreUnmatchedBook flips status to IGNORED`() = runBlocking {
        val admin = createAdminUser(username = "ub-ignore")
        val row = UnmatchedBook(file_path = "/x.epub", file_name = "x.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.ignoreUnmatchedBook(unmatchedBookIdRequest {
                unmatchedBookId = row.id!!
            })
            val refreshed = UnmatchedBook.findById(row.id!!)!!
            assertEquals(UnmatchedBookStatus.IGNORED.name, refreshed.match_status)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `ignoreUnmatchedBook returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "ub-ignore-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.ignoreUnmatchedBook(unmatchedBookIdRequest {
                    unmatchedBookId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookByIsbn rejects non-10-or-13 length`() = runBlocking {
        val admin = createAdminUser(username = "ub-isbn-len")
        val row = UnmatchedBook(file_path = "/y.epub", file_name = "y.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookByIsbn(linkUnmatchedBookByIsbnRequest {
                    unmatchedBookId = row.id!!
                    isbn = "12345" // way too short
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookByIsbn returns NOT_FOUND for unknown unmatched id`() = runBlocking {
        val admin = createAdminUser(username = "ub-isbn-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookByIsbn(linkUnmatchedBookByIsbnRequest {
                    unmatchedBookId = 999_999
                    isbn = "0553293354"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookToTitle returns NOT_FOUND for unknown ids and INVALID for non-book title`() = runBlocking {
        val admin = createAdminUser(username = "ub-link-title")
        val movie = createTitle(name = "Not A Book",
            mediaType = MediaTypeEntity.MOVIE.name)
        val row = UnmatchedBook(file_path = "/z.epub", file_name = "z.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val unknownRow = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookToTitle(linkUnmatchedBookToTitleRequest {
                    unmatchedBookId = 999_999
                    titleId = movie.id!!
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownRow.status.code)

            val unknownTitle = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookToTitle(linkUnmatchedBookToTitleRequest {
                    unmatchedBookId = row.id!!
                    titleId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownTitle.status.code)

            val notABook = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookToTitle(linkUnmatchedBookToTitleRequest {
                    unmatchedBookId = row.id!!
                    titleId = movie.id!!
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, notABook.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- unmatched audio ----------------------

    @Test
    fun `listUnmatchedAudio returns UNMATCHED rows newest-first`() = runBlocking {
        val admin = createAdminUser(username = "ua-list")
        UnmatchedAudio(file_path = "/older.flac", file_name = "older.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now().minusHours(2)).save()
        UnmatchedAudio(file_path = "/newer.flac", file_name = "newer.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).save()
        UnmatchedAudio(file_path = "/linked.flac", file_name = "linked.flac",
            match_status = UnmatchedAudioStatus.LINKED.name,
            discovered_at = LocalDateTime.now().minusHours(1)).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUnmatchedAudio(Empty.getDefaultInstance())
            assertEquals(2, resp.itemsCount, "LINKED filtered out")
            assertEquals("newer.flac", resp.itemsList.first().fileName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedAudioToTrack mutates the track file_path and links the row`() = runBlocking {
        val admin = createAdminUser(username = "ua-link")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "Track 1",
            file_path = null).apply { save() }
        val row = UnmatchedAudio(file_path = "/t1.flac", file_name = "t1.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.linkUnmatchedAudioToTrack(linkUnmatchedAudioToTrackRequest {
                unmatchedAudioId = row.id!!
                trackId = track.id!!
            })
            assertEquals(track.id, resp.trackId)
            assertEquals("Track 1", resp.trackName)
            assertEquals("Album", resp.albumName)

            val refreshedTrack = Track.findById(track.id!!)!!
            assertEquals("/t1.flac", refreshedTrack.file_path)
            val refreshedRow = UnmatchedAudio.findById(row.id!!)!!
            assertEquals(UnmatchedAudioStatus.LINKED.name, refreshedRow.match_status)
            assertEquals(track.id, refreshedRow.linked_track_id)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedAudioToTrack refuses when track is already linked to a different file`() = runBlocking {
        val admin = createAdminUser(username = "ua-link-conflict")
        val album = createTitle(name = "Album",
            mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(title_id = album.id!!, track_number = 1,
            disc_number = 1, name = "T",
            file_path = "/already-linked.flac").apply { save() }
        val row = UnmatchedAudio(file_path = "/different.flac",
            file_name = "different.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedAudioToTrack(linkUnmatchedAudioToTrackRequest {
                    unmatchedAudioId = row.id!!
                    trackId = track.id!!
                })
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedAudioToTrack returns NOT_FOUND for unknown ids`() = runBlocking {
        val admin = createAdminUser(username = "ua-link-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedAudioToTrack(linkUnmatchedAudioToTrackRequest {
                    unmatchedAudioId = 999_999
                    trackId = 1
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `ignoreUnmatchedAudio flips status to IGNORED`() = runBlocking {
        val admin = createAdminUser(username = "ua-ignore")
        val row = UnmatchedAudio(file_path = "/i.flac", file_name = "i.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.ignoreUnmatchedAudio(unmatchedAudioIdRequest {
                unmatchedAudioId = row.id!!
            })
            val refreshed = UnmatchedAudio.findById(row.id!!)!!
            assertEquals(UnmatchedAudioStatus.IGNORED.name, refreshed.match_status)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `ignoreUnmatchedAudio returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "ua-ignore-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.ignoreUnmatchedAudio(unmatchedAudioIdRequest {
                    unmatchedAudioId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listUnmatchedAudioGroups returns empty when nothing seeded`() = runBlocking {
        val admin = createAdminUser(username = "uag-empty")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUnmatchedAudioGroups(Empty.getDefaultInstance())
            assertEquals(0, resp.groupsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- searchCatalogTitles ----------------------

    @Test
    fun `searchCatalogTitles returns empty when query is too short`() = runBlocking {
        val admin = createAdminUser(username = "sct-short")
        createTitle(name = "Whatever")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTitles(searchCatalogTitlesRequest {
                query = "x"  // < 2 chars
            })
            assertEquals(0, resp.matchesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchCatalogTitles narrows by name substring with optional media_type filter`() = runBlocking {
        val admin = createAdminUser(username = "sct-list")
        createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name)
        createTitle(name = "Foundation Movie",
            mediaType = MediaTypeEntity.MOVIE.name)
        createTitle(name = "Asimov Robots",
            mediaType = MediaTypeEntity.BOOK.name)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val all = stub.searchCatalogTitles(searchCatalogTitlesRequest {
                query = "foundation"
            })
            assertEquals(2, all.matchesCount, "matches both Foundation entries")

            val booksOnly = stub.searchCatalogTitles(searchCatalogTitlesRequest {
                query = "foundation"
                mediaType = MediaType.MEDIA_TYPE_BOOK
            })
            assertEquals(1, booksOnly.matchesCount,
                "media_type filter narrows to BOOK")
            assertEquals("Foundation", booksOnly.matchesList.single().name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchCatalogTitles excludes hidden titles`() = runBlocking {
        val admin = createAdminUser(username = "sct-hidden")
        createTitle(name = "Visible Foundation").apply { hidden = false; save() }
        createTitle(name = "Hidden Foundation").apply { hidden = true; save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchCatalogTitles(searchCatalogTitlesRequest {
                query = "foundation"
            })
            val names = resp.matchesList.map { it.name }
            assertTrue("Visible Foundation" in names)
            assertFalse(names.any { it.contains("Hidden") })
        } finally {
            authed.shutdownNow()
        }
    }
}

