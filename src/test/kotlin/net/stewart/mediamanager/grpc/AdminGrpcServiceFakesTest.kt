package net.stewart.mediamanager.grpc

import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEntity
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEntity
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleAuthor
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.UnmatchedAudio
import net.stewart.mediamanager.entity.UnmatchedAudioStatus
import net.stewart.mediamanager.entity.UnmatchedBook
import net.stewart.mediamanager.entity.UnmatchedBookStatus
import net.stewart.mediamanager.service.ArtistEnrichmentAgent
import net.stewart.mediamanager.service.AuthorEnrichmentAgent
import net.stewart.mediamanager.service.FakeHttpFetcher
import net.stewart.mediamanager.service.FakeMusicBrainzService
import net.stewart.mediamanager.service.FakeOpenLibraryService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.TestClock
import net.stewart.mediamanager.service.MusicBrainzArtistCredit
import net.stewart.mediamanager.service.MusicBrainzReleaseLookup
import net.stewart.mediamanager.service.MusicBrainzResult
import net.stewart.mediamanager.service.MusicBrainzTrack
import net.stewart.mediamanager.service.OpenLibraryAuthor
import net.stewart.mediamanager.service.OpenLibraryBookLookup
import net.stewart.mediamanager.service.OpenLibraryResult
import net.stewart.mediamanager.service.OpenLibrarySearchHit
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 16 of [AdminGrpcService] coverage — exercises the
 * MusicBrainz/Open Library code paths that the production service used
 * to instantiate over real HTTP. The constructor now takes both
 * collaborators as parameters, so this class spins up its own
 * InProcess gRPC server with [FakeMusicBrainzService] /
 * [FakeOpenLibraryService] in place of the HTTP impls. The fakes are
 * mutable, so each test scripts the responses it expects before calling
 * the RPC.
 */
class AdminGrpcServiceFakesTest : GrpcTestBase() {

    companion object {
        private const val FAKES_SERVER_NAME = "grpc-test-fakes-server"

        private lateinit var fakeServer: io.grpc.Server
        lateinit var fakeMb: FakeMusicBrainzService
            private set
        lateinit var fakeOl: FakeOpenLibraryService
            private set
        lateinit var fakeHttp: FakeHttpFetcher
            private set

        @BeforeClass @JvmStatic
        fun setupFakesServer() {
            fakeMb = FakeMusicBrainzService()
            fakeOl = FakeOpenLibraryService()
            fakeHttp = FakeHttpFetcher()
            val admin = AdminGrpcService(
                openLibrary = fakeOl,
                unmatchedAudioMb = fakeMb,
                // Synchronous executor — reEnrichWithAgent's lambda runs on
                // the calling thread before the RPC returns, so tests can
                // assert the side effects without polling.
                reEnrichExecutor = java.util.concurrent.Executor { it.run() },
                // Per-call agent factories built around the same fakeHttp
                // fetcher so tests can script OL / MB / Wikipedia responses
                // deterministically. TestClock short-circuits the API_GAP
                // sleeps the agents put between fetches.
                authorAgentFactory = {
                    AuthorEnrichmentAgent(clock = TestClock(), http = fakeHttp)
                },
                artistAgentFactory = {
                    ArtistEnrichmentAgent(clock = TestClock(), http = fakeHttp)
                },
            )
            fakeServer = InProcessServerBuilder.forName(FAKES_SERVER_NAME)
                .directExecutor()
                .addService(
                    ServerInterceptors.intercept(
                        admin,
                        LoggingInterceptor(),
                        AuthInterceptor(),
                    )
                )
                .build()
                .start()
        }

        @AfterClass @JvmStatic
        fun teardownFakesServer() {
            fakeServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Before
    fun resetFakesAndUnmatchedTables() {
        // Base class doesn't reset these — they have unique-index constraints
        // on file_path that bite across tests.
        UnmatchedAudio.deleteAll()
        UnmatchedBook.deleteAll()
        Track.deleteAll()
        // Authors / Artists for the agent-dispatch tests.
        TitleAuthor.deleteAll()
        TitleArtist.deleteAll()
        Author.deleteAll()
        Artist.deleteAll()

        fakeMb.byBarcode = emptyMap()
        fakeMb.byReleaseMbid = emptyMap()
        fakeMb.byArtistAndAlbum = emptyMap()
        fakeOl.byIsbn = emptyMap()
        fakeOl.bySearch = emptyMap()
        fakeHttp.responses = emptyMap()
        fakeHttp.requestedUrls.clear()
    }

    private fun fakeAdminChannel(user: AppUser): ManagedChannel {
        val tokenPair = JwtService.createTokenPair(user, "test")
        val metadata = Metadata().apply {
            put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer ${tokenPair.accessToken}",
            )
        }
        return InProcessChannelBuilder.forName(FAKES_SERVER_NAME)
            .directExecutor()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build()
    }

    // ---------------------- linkUnmatchedBookByIsbn ----------------------

    @Test
    fun `linkUnmatchedBookByIsbn ingests via OpenLibrary on Success`() = runBlocking {
        val admin = createAdminUser(username = "fake-isbn-ok")
        val row = UnmatchedBook(
            file_path = "/library/asimov.epub", file_name = "asimov.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        fakeOl.byIsbn = mapOf(
            "9780553293357" to OpenLibraryResult.Success(
                OpenLibraryBookLookup(
                    openLibraryWorkId = "OL46125W",
                    openLibraryEditionId = "OL7440033M",
                    workTitle = "Foundation",
                    isbn = "9780553293357",
                    rawPhysicalFormat = "Paperback",
                    mediaFormat = MediaFormat.MASS_MARKET_PAPERBACK.name,
                    pageCount = 244,
                    editionYear = 1991,
                    firstPublicationYear = 1951,
                    description = "Hari Seldon's psychohistory.",
                    coverUrl = null,
                    authors = listOf(OpenLibraryAuthor("OL34184A", "Isaac Asimov")),
                    series = emptyList(),
                    rawJson = """{"mock":true}""",
                )
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.linkUnmatchedBookByIsbn(linkUnmatchedBookByIsbnRequest {
                unmatchedBookId = row.id!!
                isbn = "9780553293357"
            })
            assertEquals("Foundation", resp.titleName)
            assertTrue(resp.createdNewTitle, "first ingest creates a fresh Title")
            // Row flipped to LINKED.
            val refreshed = UnmatchedBook.findById(row.id!!)!!
            assertEquals(UnmatchedBookStatus.LINKED.name, refreshed.match_status)
            assertEquals(resp.titleId, refreshed.linked_title_id)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookByIsbn translates OpenLibrary NotFound to NOT_FOUND`() = runBlocking {
        val admin = createAdminUser(username = "fake-isbn-miss")
        val row = UnmatchedBook(
            file_path = "/library/missing.epub", file_name = "missing.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        // No script for "0000000000000" → fake returns NotFound by default.

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookByIsbn(linkUnmatchedBookByIsbnRequest {
                    unmatchedBookId = row.id!!
                    isbn = "0000000000000"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
            assertTrue(ex.status.description!!.contains("no record"))
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedBookByIsbn surfaces OpenLibrary Error message in NOT_FOUND`() = runBlocking {
        val admin = createAdminUser(username = "fake-isbn-err")
        val row = UnmatchedBook(
            file_path = "/library/x.epub", file_name = "x.epub",
            media_format = MediaFormat.EBOOK_EPUB.name,
            match_status = UnmatchedBookStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        fakeOl.byIsbn = mapOf(
            "1234567890123" to OpenLibraryResult.Error(message = "rate limited", rateLimited = true),
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedBookByIsbn(linkUnmatchedBookByIsbnRequest {
                    unmatchedBookId = row.id!!
                    isbn = "1234567890123"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
            assertTrue(ex.status.description!!.contains("rate limited"),
                "Error.message must propagate to the client")
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- searchOpenLibrary ----------------------

    @Test
    fun `searchOpenLibrary forwards trimmed query and clamped limit to OpenLibrary`() = runBlocking {
        val admin = createAdminUser(username = "fake-search-ok")
        fakeOl.bySearch = mapOf(
            "foundation" to listOf(
                OpenLibrarySearchHit(
                    workId = "OL46125W",
                    title = "Foundation",
                    authors = listOf("Isaac Asimov"),
                    firstPublishYear = 1951,
                    coverId = null,
                    isbn = "9780553293357",
                ),
                OpenLibrarySearchHit(
                    workId = "OL46126W",
                    title = "Foundation and Empire",
                    authors = listOf("Isaac Asimov"),
                    firstPublishYear = 1952,
                    coverId = null,
                    isbn = null,
                ),
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.searchOpenLibrary(searchOpenLibraryRequest {
                query = "  foundation  "
                limit = 5
            })
            assertEquals(2, resp.hitsCount)
            assertEquals("OL46125W", resp.hitsList[0].openlibraryWorkId)
            assertEquals("Isaac Asimov", resp.hitsList[0].authorName)
            assertEquals(1951, resp.hitsList[0].firstPublishYear)
            assertEquals("Foundation and Empire", resp.hitsList[1].title)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `searchOpenLibrary returns empty for queries shorter than 2 chars without hitting OL`() = runBlocking {
        val admin = createAdminUser(username = "fake-search-short")
        // Script a hit at "x" — the RPC must not look it up because the
        // length check fires first.
        fakeOl.bySearch = mapOf(
            "x" to listOf(
                OpenLibrarySearchHit("OL999W", "Should not appear",
                    listOf("Nobody"), null, null, null)
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.searchOpenLibrary(searchOpenLibraryRequest { query = "x" })
            assertEquals(0, resp.hitsCount)
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- searchMusicBrainzForUnmatchedAudio ----------------------

    /**
     * MB IDs (release / artist / recording) are stored as VARCHAR(36) so
     * fakes must use real UUID-shape strings. Generate fresh UUIDs per
     * call so unique constraints don't bite when several fake releases
     * coexist in the same test.
     */
    private fun fakeRelease(
        mbid: String,
        title: String,
        tracks: List<MusicBrainzTrack>,
        barcode: String? = null,
    ) = MusicBrainzReleaseLookup(
        musicBrainzReleaseId = mbid,
        musicBrainzReleaseGroupId = java.util.UUID.randomUUID().toString(),
        title = title,
        albumArtistCredits = listOf(MusicBrainzArtistCredit(
            musicBrainzArtistId = java.util.UUID.randomUUID().toString(),
            name = "Artist X",
            type = "Group",
            sortName = "Artist X",
        )),
        releaseYear = 2020,
        label = "Label X",
        barcode = barcode,
        tracks = tracks,
        totalDurationSeconds = tracks.mapNotNull { it.durationSeconds }.sum().takeIf { it > 0 },
        rawJson = """{"mock":true}""",
    )

    private fun fakeTrack(disc: Int, num: Int) = MusicBrainzTrack(
        musicBrainzRecordingId = java.util.UUID.randomUUID().toString(),
        trackNumber = num,
        discNumber = disc,
        name = "Track $num",
        durationSeconds = 180,
        trackArtistCredits = emptyList(),
    )

    @Test
    fun `searchMusicBrainzForUnmatchedAudio with direct MBID override skips search tiers`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-direct")
        val row = UnmatchedAudio(
            file_path = "/music/01.flac", file_name = "01.flac",
            parsed_track_number = 1, parsed_disc_number = 1,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        val mbid = java.util.UUID.randomUUID().toString()
        fakeMb.byReleaseMbid = mapOf(
            mbid to MusicBrainzResult.Success(
                fakeRelease(mbid, "Direct Album", listOf(fakeTrack(1, 1)))
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.searchMusicBrainzForUnmatchedAudio(
                searchMusicBrainzForUnmatchedAudioRequest {
                    unmatchedAudioIds.add(row.id!!)
                    queryOverride = mbid
                }
            )
            assertEquals("(direct MBID lookup)", resp.searchArtist)
            assertEquals(mbid, resp.searchAlbum)
            assertEquals(1, resp.candidatesCount)
            assertEquals("Direct Album", resp.candidatesList.single().title)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `searchMusicBrainzForUnmatchedAudio combines barcode and artist+album hits`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-combo")
        val rows = (1..2).map { i ->
            UnmatchedAudio(
                file_path = "/music/0$i.flac", file_name = "0$i.flac",
                parsed_album = "My Album",
                parsed_album_artist = "My Artist",
                parsed_upc = "111111111111",
                parsed_track_number = i, parsed_disc_number = 1,
                match_status = UnmatchedAudioStatus.UNMATCHED.name,
                discovered_at = LocalDateTime.now(),
            ).apply { save() }
        }
        val barcodeMbid = java.util.UUID.randomUUID().toString()
        val searchMbid = java.util.UUID.randomUUID().toString()
        // Barcode hit returns one release; artist+album search returns the same MBID
        // plus another so the dedup path is exercised.
        fakeMb.byBarcode = mapOf(
            "111111111111" to MusicBrainzResult.Success(
                fakeRelease(barcodeMbid, "Barcode Match",
                    (1..2).map { fakeTrack(1, it) })
            )
        )
        fakeMb.byArtistAndAlbum = mapOf(
            ("My Artist" to "My Album") to listOf(barcodeMbid, searchMbid)
        )
        fakeMb.byReleaseMbid = mapOf(
            barcodeMbid to MusicBrainzResult.Success(
                fakeRelease(barcodeMbid, "Barcode Match",
                    (1..2).map { fakeTrack(1, it) })
            ),
            searchMbid to MusicBrainzResult.Success(
                fakeRelease(searchMbid, "Search Match",
                    (1..2).map { fakeTrack(1, it) })
            ),
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.searchMusicBrainzForUnmatchedAudio(
                searchMusicBrainzForUnmatchedAudioRequest {
                    rows.forEach { unmatchedAudioIds.add(it.id!!) }
                }
            )
            assertEquals("My Artist", resp.searchArtist)
            assertEquals("My Album", resp.searchAlbum)
            // Barcode mbid + search mbid (deduped) → 2 candidates.
            assertEquals(2, resp.candidatesCount)
            val titles = resp.candidatesList.map { it.title }
            assertTrue("Barcode Match" in titles)
            assertTrue("Search Match" in titles)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `searchMusicBrainzForUnmatchedAudio with no-MBID override parses artist - album`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-override")
        val row = UnmatchedAudio(
            file_path = "/music/x.flac", file_name = "x.flac",
            parsed_track_number = 1, parsed_disc_number = 1,
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        val mbid = java.util.UUID.randomUUID().toString()
        fakeMb.byArtistAndAlbum = mapOf(
            ("Pink Floyd" to "Animals") to listOf(mbid)
        )
        fakeMb.byReleaseMbid = mapOf(
            mbid to MusicBrainzResult.Success(
                fakeRelease(mbid, "Animals", listOf(fakeTrack(1, 1)))
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.searchMusicBrainzForUnmatchedAudio(
                searchMusicBrainzForUnmatchedAudioRequest {
                    unmatchedAudioIds.add(row.id!!)
                    queryOverride = "Pink Floyd - Animals"
                }
            )
            assertEquals("Pink Floyd", resp.searchArtist)
            assertEquals("Animals", resp.searchAlbum)
            assertEquals(1, resp.candidatesCount)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `searchMusicBrainzForUnmatchedAudio returns NOT_FOUND when no rows match`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-empty")
        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val ex = assertFailsWith<StatusException> {
                stub.searchMusicBrainzForUnmatchedAudio(
                    searchMusicBrainzForUnmatchedAudioRequest {
                        unmatchedAudioIds.add(999_999)
                    }
                )
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- linkUnmatchedAudioAlbumToRelease ----------------------

    @Test
    fun `linkUnmatchedAudioAlbumToRelease ingests via MusicBrainz and links matching tracks`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-link-ok")
        val rows = (1..3).map { i ->
            UnmatchedAudio(
                file_path = "/music/album-y/0$i.flac", file_name = "0$i.flac",
                parsed_album = "Album Y",
                parsed_album_artist = "Artist Y",
                parsed_track_number = i, parsed_disc_number = 1,
                match_status = UnmatchedAudioStatus.UNMATCHED.name,
                discovered_at = LocalDateTime.now(),
            ).apply { save() }
        }
        val mbid = java.util.UUID.randomUUID().toString()
        fakeMb.byReleaseMbid = mapOf(
            mbid to MusicBrainzResult.Success(
                fakeRelease(mbid, "Album Y",
                    (1..3).map { fakeTrack(1, it) })
            )
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val resp = stub.linkUnmatchedAudioAlbumToRelease(
                linkUnmatchedAudioAlbumToReleaseRequest {
                    rows.forEach { unmatchedAudioIds.add(it.id!!) }
                    releaseMbid = mbid
                }
            )
            assertEquals("Album Y", resp.titleName)
            assertEquals(3, resp.linked, "all three rows linked to fresh tracks")
            assertEquals(0, resp.failedCount)

            val tracks = Track.findAll().filter { it.title_id == resp.titleId }
                .sortedBy { it.track_number }
            assertEquals(3, tracks.size)
            assertEquals(listOf("/music/album-y/01.flac",
                "/music/album-y/02.flac",
                "/music/album-y/03.flac"),
                tracks.map { it.file_path })
            for (row in rows) {
                val refreshed = UnmatchedAudio.findById(row.id!!)!!
                assertEquals(UnmatchedAudioStatus.LINKED.name, refreshed.match_status)
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatchedAudioAlbumToRelease returns NOT_FOUND when MB does not know the release`() = runBlocking {
        val admin = createAdminUser(username = "fake-mb-link-miss")
        val row = UnmatchedAudio(
            file_path = "/music/x.flac", file_name = "x.flac",
            match_status = UnmatchedAudioStatus.UNMATCHED.name,
            discovered_at = LocalDateTime.now(),
        ).apply { save() }
        // No script for the mbid → fakeMb returns NotFound.

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatchedAudioAlbumToRelease(
                    linkUnmatchedAudioAlbumToReleaseRequest {
                        unmatchedAudioIds.add(row.id!!)
                        releaseMbid = java.util.UUID.randomUUID().toString()
                    }
                )
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- reEnrichWithAgent ----------------------

    /**
     * The synchronous executor wired up in [setupFakesServer] makes
     * reEnrichWithAgent's body run on the calling thread, so the side
     * effects (status flip, agent dispatch loops) are observable
     * immediately after the RPC returns.
     */

    @Test
    fun `reEnrichWithAgent flips status to PENDING for TMDB OL and MB agent kinds`() = runBlocking {
        val admin = createAdminUser(username = "rewa-status-reset")
        val cases = listOf(
            EnrichmentAgent.ENRICHMENT_AGENT_TMDB,
            EnrichmentAgent.ENRICHMENT_AGENT_OPENLIBRARY,
            EnrichmentAgent.ENRICHMENT_AGENT_MUSICBRAINZ,
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            for (agent in cases) {
                val title = createTitle(name = "T-${agent.name}")
                    .apply {
                        enrichment_status = EnrichmentStatusEntity.ENRICHED.name
                        save()
                    }
                stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                    titleId = title.id!!
                    this.agent = agent
                })
                val refreshed = Title.findById(title.id!!)!!
                assertEquals(EnrichmentStatusEntity.PENDING.name,
                    refreshed.enrichment_status,
                    "agent $agent must reset enrichment_status")
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent AUTHOR_HEADSHOT walks linked authors with no-op when none are linked`() = runBlocking {
        val admin = createAdminUser(username = "rewa-author")
        val title = createTitle(name = "Bookless",
            mediaType = MediaTypeEntity.BOOK.name)
        // No TitleAuthor links → forEach loop runs over an empty list;
        // the AuthorEnrichmentAgent constructor builds with no I/O.

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                titleId = title.id!!
                agent = EnrichmentAgent.ENRICHMENT_AGENT_AUTHOR_HEADSHOT
            })
            // Status not touched on this path.
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(EnrichmentStatusEntity.ENRICHED.name,
                refreshed.enrichment_status,
                "AUTHOR_HEADSHOT path must not flip enrichment_status")
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent ARTIST_HEADSHOT and ARTIST_PERSONNEL walk linked artists`() = runBlocking {
        val admin = createAdminUser(username = "rewa-artist")
        val title = createTitle(name = "Artistless",
            mediaType = MediaTypeEntity.ALBUM.name)
        // No TitleArtist links → both branches share the same body and
        // the forEach loop over an empty list returns without HTTP I/O.

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            for (agent in listOf(
                EnrichmentAgent.ENRICHMENT_AGENT_ARTIST_HEADSHOT,
                EnrichmentAgent.ENRICHMENT_AGENT_ARTIST_PERSONNEL,
            )) {
                stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                    titleId = title.id!!
                    this.agent = agent
                })
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent UNKNOWN agent falls through to the warn log without throwing`() = runBlocking {
        val admin = createAdminUser(username = "rewa-unknown")
        val title = createTitle(name = "WhoKnows")

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                titleId = title.id!!
                agent = EnrichmentAgent.ENRICHMENT_AGENT_UNKNOWN
            })
            // No-op: status preserved.
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(EnrichmentStatusEntity.ENRICHED.name,
                refreshed.enrichment_status)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent AUTHOR_HEADSHOT runs AuthorEnrichmentAgent over linked authors`() = runBlocking {
        val admin = createAdminUser(username = "rewa-author-ok")
        val title = createTitle(name = "Foundation",
            mediaType = MediaTypeEntity.BOOK.name)
        val author = Author(
            name = "Isaac Asimov", sort_name = "Asimov, Isaac",
            open_library_author_id = "OL34184A",
        ).apply { save() }
        TitleAuthor(title_id = title.id!!, author_id = author.id!!,
            author_order = 0).save()

        // Script the OL author endpoint with bio + wikidata + birth_date.
        // Headshot stays null so needsWikipediaData returns true and the
        // Wikipedia branch fires too; we leave its URLs un-scripted, so the
        // fetcher returns null and the Wikipedia path bails cleanly.
        fakeHttp.responses = mapOf(
            "https://openlibrary.org/authors/OL34184A.json" to """
                {
                    "bio": "Isaac Asimov was a prolific American writer and biochemist.",
                    "remote_ids": {"wikidata": "Q34981"},
                    "birth_date": "1920-01-02",
                    "death_date": "1992-04-06"
                }
            """.trimIndent(),
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                titleId = title.id!!
                agent = EnrichmentAgent.ENRICHMENT_AGENT_AUTHOR_HEADSHOT
            })
            val refreshed = Author.findById(author.id!!)!!
            assertTrue(refreshed.biography!!.contains("biochemist"))
            assertEquals("Q34981", refreshed.wikidata_id)
            assertEquals(java.time.LocalDate.of(1920, 1, 2),
                refreshed.birth_date)
            assertTrue(fakeHttp.requestedUrls.any { it.endsWith("OL34184A.json") },
                "OL author endpoint must have been hit")
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reEnrichWithAgent ARTIST_HEADSHOT runs ArtistEnrichmentAgent over linked artists`() = runBlocking {
        val admin = createAdminUser(username = "rewa-artist-ok")
        val title = createTitle(name = "The Wall",
            mediaType = MediaTypeEntity.ALBUM.name)
        val artistMbid = java.util.UUID.randomUUID().toString()
        val artist = Artist(
            name = "Pink Floyd", sort_name = "Pink Floyd",
            artist_type = ArtistTypeEntity.GROUP.name,
            musicbrainz_artist_id = artistMbid,
        ).apply { save() }
        TitleArtist(title_id = title.id!!, artist_id = artist.id!!,
            artist_order = 0).save()

        // MB artist endpoint: life-span gives begin_date, relations have a
        // wikidata URL the agent strips down to the Q-number, and
        // disambiguation seeds the bio.
        fakeHttp.responses = mapOf(
            "https://musicbrainz.org/ws/2/artist/$artistMbid?inc=url-rels&fmt=json" to """
                {
                    "disambiguation": "English progressive rock band",
                    "life-span": {"begin": "1965", "ended": "false"},
                    "relations": [
                        {"type": "wikidata",
                         "url": {"resource": "https://www.wikidata.org/wiki/Q2306"}}
                    ]
                }
            """.trimIndent(),
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            stub.reEnrichWithAgent(reEnrichWithAgentRequest {
                titleId = title.id!!
                agent = EnrichmentAgent.ENRICHMENT_AGENT_ARTIST_HEADSHOT
            })
            val refreshed = Artist.findById(artist.id!!)!!
            assertEquals("English progressive rock band", refreshed.biography)
            assertEquals("Q2306", refreshed.wikidata_id)
            // begin_date parsed from year-only "1965" → 1965-01-01.
            assertEquals(java.time.LocalDate.of(1965, 1, 1),
                refreshed.begin_date)
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- triggerArtistEnrichment ----------------------

    @Test
    fun `triggerArtistEnrichment dispatches the agent for a known artist`() = runBlocking {
        val admin = createAdminUser(username = "tae-ok")
        val artistMbid = java.util.UUID.randomUUID().toString()
        val artist = Artist(
            name = "The Beatles", sort_name = "Beatles, The",
            artist_type = ArtistTypeEntity.GROUP.name,
            musicbrainz_artist_id = artistMbid,
        ).apply { save() }
        fakeHttp.responses = mapOf(
            "https://musicbrainz.org/ws/2/artist/$artistMbid?inc=url-rels&fmt=json" to """
                {
                    "disambiguation": "English rock band from Liverpool",
                    "life-span": {"begin": "1960"},
                    "relations": []
                }
            """.trimIndent(),
        )

        val channel = fakeAdminChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
            stub.triggerArtistEnrichment(adminArtistIdRequest { artistId = artist.id!! })
            val refreshed = Artist.findById(artist.id!!)!!
            assertEquals("English rock band from Liverpool", refreshed.biography)
            assertEquals(java.time.LocalDate.of(1960, 1, 1), refreshed.begin_date)
        } finally {
            channel.shutdownNow()
        }
    }
}
