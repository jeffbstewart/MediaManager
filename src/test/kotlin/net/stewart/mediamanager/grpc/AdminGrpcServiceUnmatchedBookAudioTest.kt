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

    @Test
    fun `linkUnmatchedBookToTitle links the file to the title and flips the row to LINKED`() = runBlocking {
        val admin = createAdminUser(username = "ub-link-ok")
        val book = createTitle(name = "Real Book",
            mediaType = MediaTypeEntity.BOOK.name)
        val row = UnmatchedBook(file_path = "/library/sibling.epub",
            file_name = "sibling.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.linkUnmatchedBookToTitle(linkUnmatchedBookToTitleRequest {
                unmatchedBookId = row.id!!
                titleId = book.id!!
            })
            assertEquals(book.id!!, resp.titleId)
            assertEquals("Real Book", resp.titleName)
            assertFalse(resp.createdNewTitle, "sibling-link reuses the title")

            val refreshed = UnmatchedBook.findById(row.id!!)!!
            assertEquals(UnmatchedBookStatus.LINKED.name, refreshed.match_status)
            assertEquals(book.id!!, refreshed.linked_title_id)
            // BookIngestionService creates a new MediaItem for the sibling edition.
            val item = net.stewart.mediamanager.entity.MediaItem.findAll().single()
            assertEquals("/library/sibling.epub", item.file_path)
            assertEquals(MediaFormat.EBOOK_EPUB.name, item.media_format)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookToTitle falls back to EBOOK_EPUB when row format is invalid`() = runBlocking {
        val admin = createAdminUser(username = "ub-link-bad-fmt")
        val book = createTitle(name = "Book", mediaType = MediaTypeEntity.BOOK.name)
        // Row with a non-MediaFormat string forces the runCatching fallback.
        val row = UnmatchedBook(file_path = "/library/x.epub",
            file_name = "x.epub",
            media_format = "NOT_A_REAL_FORMAT",
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.linkUnmatchedBookToTitle(linkUnmatchedBookToTitleRequest {
                unmatchedBookId = row.id!!
                titleId = book.id!!
            })
            val item = net.stewart.mediamanager.entity.MediaItem.findAll().single()
            assertEquals(MediaFormat.EBOOK_EPUB.name, item.media_format,
                "invalid row format falls back to EBOOK_EPUB default")
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

    // ---------------------- linkUnmatchedAudioAlbumManual ----------------------

    @Test
    fun `linkUnmatchedAudioAlbumManual ingests a manual album and links every matched track`() = runBlocking {
        val admin = createAdminUser(username = "ua-manual-ok")
        // Three rows that all share an album_artist + album so MusicIngestionService
        // creates one Title with three Tracks; track_number drives the link.
        val rows = (1..3).map { i ->
            UnmatchedAudio(
                file_path = "/music/album-x/0$i.flac",
                file_name = "0$i.flac",
                parsed_album = "Album X",
                parsed_album_artist = "Artist X",
                parsed_track_artist = "Artist X",
                parsed_track_number = i,
                parsed_disc_number = 1,
                parsed_title = "Track $i",
                parsed_duration_seconds = 200,
                match_status = UnmatchedAudioStatus.UNMATCHED.name,
                discovered_at = LocalDateTime.now()
            ).apply { save() }
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.linkUnmatchedAudioAlbumManual(
                linkUnmatchedAudioAlbumManualRequest {
                    rows.forEach { unmatchedAudioIds.add(it.id!!) }
                }
            )
            assertEquals("Album X", resp.titleName)
            assertEquals(3, resp.linked, "all three rows linked to fresh tracks")
            assertEquals(0, resp.failedCount)

            // Tracks were created and now point at the source files.
            val tracks = Track.findAll().filter { it.title_id == resp.titleId }
                .sortedBy { it.track_number }
            assertEquals(3, tracks.size)
            assertEquals(listOf("/music/album-x/01.flac",
                "/music/album-x/02.flac",
                "/music/album-x/03.flac"),
                tracks.map { it.file_path })
            // Rows flipped to LINKED with linked_track_id set.
            for (row in rows) {
                val refreshed = UnmatchedAudio.findById(row.id!!)!!
                assertEquals(UnmatchedAudioStatus.LINKED.name, refreshed.match_status)
                assertTrue(refreshed.linked_track_id != null)
            }
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedAudioAlbumManual returns NOT_FOUND when no row ids match`() = runBlocking {
        val admin = createAdminUser(username = "ua-manual-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedAudioAlbumManual(
                    linkUnmatchedAudioAlbumManualRequest {
                        unmatchedAudioIds.add(999_999)
                    }
                )
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }
}

