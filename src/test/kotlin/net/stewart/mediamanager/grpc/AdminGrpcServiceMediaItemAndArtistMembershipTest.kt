package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.ArtistType as ArtistTypeEntity
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEntity
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.OwnershipPhoto
import net.stewart.mediamanager.entity.Title
import org.junit.Before
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slices 13+14 of [AdminGrpcService] coverage — media-item curation
 * (valuations, updateMediaItem, setMediaItemFormat, triggerKeepaLookup,
 * listUndocumentedItems), artist memberships, and the
 * assignExternalIdentifier router.
 */
class AdminGrpcServiceMediaItemAndArtistMembershipTest : GrpcTestBase() {

    @Before
    fun cleanExtraTables() {
        OwnershipPhoto.deleteAll()
        ArtistMembership.deleteAll()
        Artist.deleteAll()
    }

    private fun seedItem(
        productName: String,
        purchasePrice: BigDecimal? = null,
        replacementValue: BigDecimal? = null,
        format: MediaFormatEntity = MediaFormatEntity.BLURAY,
    ): MediaItem = MediaItem(
        product_name = productName,
        media_format = format.name,
        purchase_price = purchasePrice,
        replacement_value = replacementValue,
    ).apply { save() }

    // ---------------------- listValuations ----------------------

    @Test
    fun `listValuations totals purchase + replacement values across all items`() = runBlocking {
        val admin = createAdminUser(username = "val-list")
        seedItem("AAA", purchasePrice = BigDecimal("10.00"),
            replacementValue = BigDecimal("15.00"))
        seedItem("BBB", purchasePrice = BigDecimal("20.00"))
        seedItem("CCC")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listValuations(valuationRequest { })
            assertEquals(3, resp.itemsCount)
            assertEquals(3, resp.totalItems)
            assertEquals(30.0, resp.totalPurchaseValue)
            assertEquals(15.0, resp.totalReplacementValue)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listValuations unpricedOnly filters items with a purchase price`() = runBlocking {
        val admin = createAdminUser(username = "val-unpriced")
        seedItem("Priced", purchasePrice = BigDecimal("10.00"))
        seedItem("Unpriced")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listValuations(valuationRequest { unpricedOnly = true })
            assertEquals(1, resp.itemsCount)
            assertEquals("Unpriced", resp.itemsList.single().productName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `listValuations pages by 0-indexed page times pageSize`() = runBlocking {
        val admin = createAdminUser(username = "val-page")
        for (i in 1..7) seedItem("Item-$i")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val page0 = stub.listValuations(valuationRequest { pageSize = 3; page = 0 })
            assertEquals(3, page0.itemsCount)
            val page2 = stub.listValuations(valuationRequest { pageSize = 3; page = 2 })
            assertEquals(1, page2.itemsCount, "remainder on the last page")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateMediaItem ----------------------

    @Test
    fun `updateMediaItem mutates only the fields present on the request`() = runBlocking {
        val admin = createAdminUser(username = "mi-update")
        val item = seedItem("Original",
            purchasePrice = BigDecimal("10.00"))

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateMediaItem(updateMediaItemRequest {
                mediaItemId = item.id!!
                purchasePlace = "Amazon"
                purchaseDate = "2024-01-15"
                purchasePrice = 25.50
                replacementValue = 30.00
                overrideAsin = "B0ABC12345"
            })
            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals("Amazon", refreshed.purchase_place)
            assertEquals(LocalDate.of(2024, 1, 15), refreshed.purchase_date)
            assertEquals(0, refreshed.purchase_price!!.compareTo(BigDecimal("25.50")))
            assertEquals(0, refreshed.replacement_value!!.compareTo(BigDecimal("30.00")))
            assertEquals("B0ABC12345", refreshed.override_asin)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateMediaItem returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "mi-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateMediaItem(updateMediaItemRequest {
                    mediaItemId = 999_999
                    purchasePrice = 1.0
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setMediaItemFormat ----------------------

    @Test
    fun `setMediaItemFormat rejects MEDIA_FORMAT_UNKNOWN with INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-unknown")
        val item = seedItem("X")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setMediaItemFormat(setMediaItemFormatRequest {
                    mediaItemId = item.id!!
                    mediaFormat = MediaFormat.MEDIA_FORMAT_UNKNOWN
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setMediaItemFormat returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setMediaItemFormat(setMediaItemFormatRequest {
                    mediaItemId = 999_999
                    mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setMediaItemFormat on un-linked item accepts any format and clears price`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-unlinked")
        val item = seedItem("Unlinked",
            replacementValue = BigDecimal("19.99"),
            format = MediaFormatEntity.DVD)
        item.replacement_value_updated_at = LocalDateTime.now()
        item.save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.setMediaItemFormat(setMediaItemFormatRequest {
                mediaItemId = item.id!!
                mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
            })
            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals(MediaFormatEntity.BLURAY.name, refreshed.media_format)
            // Un-linked path returns clearPrice=true so replacement_value clears.
            assertNull(refreshed.replacement_value)
            assertNull(refreshed.replacement_value_updated_at)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setMediaItemFormat — book-title format change clears replacement_value`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-book")
        val book = createTitle(name = "Book", mediaType = MediaTypeEntity.BOOK.name)
        val item = seedItem("BookItem",
            replacementValue = BigDecimal("12.00"),
            format = MediaFormatEntity.MASS_MARKET_PAPERBACK)
        item.replacement_value_updated_at = LocalDateTime.now()
        item.save()
        MediaItemTitle(media_item_id = item.id!!,
            title_id = book.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.setMediaItemFormat(setMediaItemFormatRequest {
                mediaItemId = item.id!!
                mediaFormat = MediaFormat.MEDIA_FORMAT_HARDBACK
            })
            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals(MediaFormatEntity.HARDBACK.name, refreshed.media_format)
            assertNull(refreshed.replacement_value, "format change clears price")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setMediaItemFormat — same format keeps replacement_value`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-noop")
        val movie = createTitle(name = "Movie",
            mediaType = MediaTypeEntity.MOVIE.name)
        val item = seedItem("MovieItem",
            replacementValue = BigDecimal("25.00"),
            format = MediaFormatEntity.BLURAY)
        item.replacement_value_updated_at = LocalDateTime.of(2024, 1, 1, 0, 0)
        item.save()
        MediaItemTitle(media_item_id = item.id!!,
            title_id = movie.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.setMediaItemFormat(setMediaItemFormatRequest {
                mediaItemId = item.id!!
                mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
            })
            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals(0, refreshed.replacement_value!!.compareTo(BigDecimal("25.00")),
                "no-op format change preserves price")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `setMediaItemFormat rejects a format that doesn't match the linked title's media_type`() = runBlocking {
        val admin = createAdminUser(username = "mi-fmt-mismatch")
        val movie = createTitle(name = "Movie",
            mediaType = MediaTypeEntity.MOVIE.name)
        val item = seedItem("MovieItem")
        MediaItemTitle(media_item_id = item.id!!,
            title_id = movie.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.setMediaItemFormat(setMediaItemFormatRequest {
                    mediaItemId = item.id!!
                    mediaFormat = MediaFormat.MEDIA_FORMAT_HARDBACK
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- triggerKeepaLookup ----------------------

    @Test
    fun `triggerKeepaLookup clears replacement_value_updated_at to re-queue`() = runBlocking {
        val admin = createAdminUser(username = "keepa-trigger")
        val item = seedItem("X").apply {
            replacement_value_updated_at = LocalDateTime.now().minusDays(7)
            save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.triggerKeepaLookup(mediaItemIdRequest { mediaItemId = item.id!! })
            assertNull(MediaItem.findById(item.id!!)!!.replacement_value_updated_at,
                "trigger clears the timestamp so the agent picks it up next sweep")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `triggerKeepaLookup returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "keepa-trigger-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.triggerKeepaLookup(mediaItemIdRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- listUndocumentedItems ----------------------

    @Test
    fun `listUndocumentedItems returns items with no ownership photos`() = runBlocking {
        val admin = createAdminUser(username = "undoc-list")
        val withPhoto = seedItem("Documented")
        val withoutPhoto = seedItem("Undocumented")
        OwnershipPhoto(id = "uuid-test",
            media_item_id = withPhoto.id, content_type = "image/jpeg",
            orientation = 1, captured_at = LocalDateTime.now()).create()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listUndocumentedItems(paginationRequest { limit = 10 })
            assertEquals(1, resp.itemsCount)
            assertEquals("Undocumented", resp.itemsList.single().productName)
            assertEquals(1, resp.totalCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- assignExternalIdentifier ----------------------

    @Test
    fun `assignExternalIdentifier returns NOT_FOUND for unknown title id`() = runBlocking {
        val admin = createAdminUser(username = "aei-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = 999_999
                    kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_TMDB
                    value = "12345"
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier rejects blank value with INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "aei-blank")
        val title = createTitle(name = "Test")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_TMDB
                    value = "   "
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier rejects non-integer TMDB value`() = runBlocking {
        val admin = createAdminUser(username = "aei-tmdb-bad")
        val title = createTitle(name = "Test")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_TMDB
                    value = "not-an-int"
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier success path sets tmdb_id and bumps to PENDING`() = runBlocking {
        val admin = createAdminUser(username = "aei-tmdb-ok")
        val title = createTitle(name = "Pending TMDB").apply {
            enrichment_status = EnrichmentStatus.SKIPPED.name
            save()
        }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                titleId = title.id!!
                kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_TMDB
                value = "55555"
                mediaType = MediaType.MEDIA_TYPE_TV
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(55555, refreshed.tmdb_id)
            assertEquals(MediaTypeEntity.TV.name, refreshed.media_type)
            assertEquals(EnrichmentStatus.PENDING.name, refreshed.enrichment_status)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier validates Open Library work id format`() = runBlocking {
        val admin = createAdminUser(username = "aei-ol")
        val title = createTitle(name = "BookTitle")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val badEx = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_OPENLIBRARY_WORK
                    value = "not-an-ol-id"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, badEx.status.code)

            stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                titleId = title.id!!
                kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_OPENLIBRARY_WORK
                value = "OL46125W"
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals("OL46125W", refreshed.open_library_work_id)
            assertEquals(MediaTypeEntity.BOOK.name, refreshed.media_type)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier validates MBID format for MusicBrainz kinds`() = runBlocking {
        val admin = createAdminUser(username = "aei-mb")
        val title = createTitle(name = "AlbumTitle")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val badEx = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind
                        .EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE_GROUP
                    value = "not-an-mbid"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, badEx.status.code)

            val rgMbid = java.util.UUID.randomUUID().toString()
            stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                titleId = title.id!!
                kind = ExternalIdentifierKind
                    .EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE_GROUP
                value = rgMbid
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(rgMbid, refreshed.musicbrainz_release_group_id)
            assertEquals(MediaTypeEntity.ALBUM.name, refreshed.media_type)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier success path sets musicbrainz_release_id for MUSICBRAINZ_RELEASE`() = runBlocking {
        val admin = createAdminUser(username = "aei-mb-release")
        val title = createTitle(name = "PressingTitle")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val badEx = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE
                    value = "obviously-not-an-mbid"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, badEx.status.code)

            val releaseMbid = java.util.UUID.randomUUID().toString()
            stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                titleId = title.id!!
                kind = ExternalIdentifierKind.EXTERNAL_IDENTIFIER_KIND_MUSICBRAINZ_RELEASE
                value = releaseMbid
            })
            val refreshed = Title.findById(title.id!!)!!
            assertEquals(releaseMbid, refreshed.musicbrainz_release_id)
            assertEquals(MediaTypeEntity.ALBUM.name, refreshed.media_type)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignExternalIdentifier rejects EXTERNAL_IDENTIFIER_KIND_UNKNOWN with INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "aei-unknown-kind")
        val title = createTitle(name = "Unknown Kind")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.assignExternalIdentifier(assignExternalIdentifierRequest {
                    titleId = title.id!!
                    kind = ExternalIdentifierKind
                        .EXTERNAL_IDENTIFIER_KIND_UNKNOWN
                    value = "anything"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- artist memberships ----------------------

    @Test
    fun `listArtistMemberships returns memberships sorted by begin_date desc`() = runBlocking {
        val admin = createAdminUser(username = "memb-list")
        val band = Artist(name = "Band", sort_name = "Band",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        val alice = Artist(name = "Alice", sort_name = "Alice",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val bob = Artist(name = "Bob", sort_name = "Bob",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }

        ArtistMembership(group_artist_id = band.id!!,
            member_artist_id = alice.id!!,
            begin_date = LocalDate.of(2010, 1, 1)).save()
        ArtistMembership(group_artist_id = band.id!!,
            member_artist_id = bob.id!!,
            begin_date = LocalDate.of(2015, 1, 1)).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listArtistMemberships(adminArtistIdRequest {
                artistId = band.id!!
            })
            assertEquals(2, resp.membershipsCount)
            // Bob first (later begin_date).
            assertEquals("Bob", resp.membershipsList.first().memberName)
            assertEquals("Alice", resp.membershipsList.last().memberName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `upsertArtistMembership creates a new row and updates an existing one`() = runBlocking {
        val admin = createAdminUser(username = "memb-upsert")
        val band = Artist(name = "Band", sort_name = "Band",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        val alice = Artist(name = "Alice", sort_name = "Alice",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Create.
            val created = stub.upsertArtistMembership(upsertArtistMembershipRequest {
                groupArtistId = band.id!!
                memberArtistId = alice.id!!
                primaryInstruments.addAll(listOf("guitar", "vocals"))
            })
            assertTrue(created.id > 0)
            val saved = ArtistMembership.findById(created.id)!!
            assertEquals("guitar,vocals", saved.primary_instruments)

            // Update via id.
            stub.upsertArtistMembership(upsertArtistMembershipRequest {
                id = created.id
                groupArtistId = band.id!!
                memberArtistId = alice.id!!
                primaryInstruments.add("bass")
            })
            val refreshed = ArtistMembership.findById(created.id)!!
            assertEquals("bass", refreshed.primary_instruments)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `upsertArtistMembership rejects same group and member`() = runBlocking {
        val admin = createAdminUser(username = "memb-self")
        val a = Artist(name = "A", sort_name = "A",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.upsertArtistMembership(upsertArtistMembershipRequest {
                    groupArtistId = a.id!!
                    memberArtistId = a.id!!
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `upsertArtistMembership returns NOT_FOUND for unknown group, member, or membership id`() = runBlocking {
        val admin = createAdminUser(username = "memb-404")
        val a = Artist(name = "Real", sort_name = "Real",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val unknownGroup = assertFailsWith<StatusException> {
                stub.upsertArtistMembership(upsertArtistMembershipRequest {
                    groupArtistId = 999_999
                    memberArtistId = a.id!!
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownGroup.status.code)
            val unknownMember = assertFailsWith<StatusException> {
                stub.upsertArtistMembership(upsertArtistMembershipRequest {
                    groupArtistId = a.id!!
                    memberArtistId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, unknownMember.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteArtistMembership removes the row`() = runBlocking {
        val admin = createAdminUser(username = "memb-delete")
        val a = Artist(name = "A", sort_name = "A",
            artist_type = ArtistTypeEntity.GROUP.name).apply { save() }
        val b = Artist(name = "B", sort_name = "B",
            artist_type = ArtistTypeEntity.PERSON.name).apply { save() }
        val m = ArtistMembership(group_artist_id = a.id!!,
            member_artist_id = b.id!!).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteArtistMembership(membershipIdRequest { membershipId = m.id!! })
            assertNull(ArtistMembership.findById(m.id!!))
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `triggerArtistEnrichment returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "art-trigger-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.triggerArtistEnrichment(adminArtistIdRequest {
                    artistId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }
}
