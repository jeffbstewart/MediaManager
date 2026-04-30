package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.BarcodeScan
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.DiscoveredFileStatus
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3 of [AdminGrpcService] coverage — submitBarcode (UPC
 * validation + dedup) and the unmatched-file action trio
 * (acceptUnmatched / ignoreUnmatched / linkUnmatched).
 */
class AdminGrpcServiceUnmatchedTest : GrpcTestBase() {

    @Before
    fun cleanExtraTables() {
        DiscoveredFile.deleteAll()
        BarcodeScan.deleteAll()
    }

    // ---------------------- submitBarcode ----------------------

    @Test
    fun `submitBarcode rejects non-numeric UPCs`() = runBlocking {
        val admin = createAdminUser(username = "admin-bc-non-num")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.submitBarcode(submitBarcodeRequest { upc = "abc12345" })
            assertEquals(SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_INVALID, resp.result)
            assertTrue(resp.message.contains("digits"))
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `submitBarcode rejects too-short and too-long UPCs`() = runBlocking {
        val admin = createAdminUser(username = "admin-bc-len")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val tooShort = stub.submitBarcode(submitBarcodeRequest { upc = "12345" })
            assertEquals(SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_INVALID, tooShort.result)
            val tooLong = stub.submitBarcode(submitBarcodeRequest {
                upc = "123456789012345"
            })
            assertEquals(SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_INVALID, tooLong.result)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `submitBarcode creates a new BarcodeScan row on first submission`() = runBlocking {
        val admin = createAdminUser(username = "admin-bc-create")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.submitBarcode(submitBarcodeRequest { upc = "0123456789012" })
            assertEquals(SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_CREATED, resp.result)
            assertTrue(resp.scanId > 0)
            // Row exists in the staging table.
            val saved = BarcodeScan.findById(resp.scanId)!!
            assertEquals("0123456789012", saved.upc)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `submitBarcode trims whitespace before validation`() = runBlocking {
        val admin = createAdminUser(username = "admin-bc-trim")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.submitBarcode(submitBarcodeRequest {
                upc = "  0123456789012  "
            })
            assertEquals(SubmitBarcodeResult.SUBMIT_BARCODE_RESULT_CREATED, resp.result)
            assertEquals("0123456789012", BarcodeScan.findById(resp.scanId)!!.upc)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- ignoreUnmatched ----------------------

    @Test
    fun `ignoreUnmatched flips DiscoveredFile status to IGNORED`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-ignore")
        val df = DiscoveredFile(file_path = "/i.mkv", file_name = "i.mkv",
            directory = "/", match_status = DiscoveredFileStatus.UNMATCHED.name
        ).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.ignoreUnmatched(unmatchedIdRequest { unmatchedId = df.id!! })
            val refreshed = DiscoveredFile.findById(df.id!!)!!
            assertEquals(DiscoveredFileStatus.IGNORED.name, refreshed.match_status)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `ignoreUnmatched returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-ignore-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.ignoreUnmatched(unmatchedIdRequest { unmatchedId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- acceptUnmatched ----------------------

    @Test
    fun `acceptUnmatched returns FAILED_PRECONDITION when DiscoveredFile has no parsed_title`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-accept-blank")
        val df = DiscoveredFile(file_path = "/blank.mkv", file_name = "blank.mkv",
            directory = "/", match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = null  // explicit
        ).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.acceptUnmatched(unmatchedIdRequest { unmatchedId = df.id!! })
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `acceptUnmatched returns NOT_FOUND when no titles match the parsed_title`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-accept-nomatch")
        // Parsed title set but no Title rows in the DB to match against.
        val df = DiscoveredFile(file_path = "/nm.mkv", file_name = "nm.mkv",
            directory = "/", match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = "Completely Unique Title Nobody Has"
        ).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.acceptUnmatched(unmatchedIdRequest { unmatchedId = df.id!! })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `acceptUnmatched returns NOT_FOUND for unknown unmatched id`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-accept-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.acceptUnmatched(unmatchedIdRequest { unmatchedId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- linkUnmatched ----------------------

    @Test
    fun `linkUnmatched returns NOT_FOUND for unknown unmatched id`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-link-404a")
        val title = createTitle(name = "TT")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatched(linkUnmatchedRequest {
                    unmatchedId = 999_999
                    titleId = title.id!!
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkUnmatched returns NOT_FOUND for unknown title id`() = runBlocking {
        val admin = createAdminUser(username = "admin-um-link-404b")
        val df = DiscoveredFile(file_path = "/l.mkv", file_name = "l.mkv",
            directory = "/", match_status = DiscoveredFileStatus.UNMATCHED.name,
            parsed_title = "Linkable"
        ).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkUnmatched(linkUnmatchedRequest {
                    unmatchedId = df.id!!
                    titleId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updatePurchaseInfo (only if scan exists) ----------------------

    @Test
    fun `getScanDetail returns NOT_FOUND for unknown scan id`() = runBlocking {
        val admin = createAdminUser(username = "admin-scandetail-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getScanDetail(scanIdRequest { scanId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `assignTmdb routes media_type and resolves through ScanDetailService`() = runBlocking {
        val admin = createAdminUser(username = "admin-assign-tmdb")
        // ScanDetailService.assignTmdb hits the DB; with no title it returns
        // NotFound which the RPC translates to NOT_FOUND.
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.assignTmdb(assignTmdbRequest {
                    titleId = 999_999
                    tmdbId = 12345
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- addTitle ----------------------

    @Test
    fun `addTitle requires tmdb_id, media_type, and media_format`() = runBlocking {
        val admin = createAdminUser(username = "admin-addtitle-blanks")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val noTmdb = assertFailsWith<StatusException> {
                stub.addTitle(addTitleRequest {
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                    mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noTmdb.status.code)

            val noMediaType = assertFailsWith<StatusException> {
                stub.addTitle(addTitleRequest {
                    tmdbId = 100
                    mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noMediaType.status.code)

            val noFormat = assertFailsWith<StatusException> {
                stub.addTitle(addTitleRequest {
                    tmdbId = 100
                    mediaType = MediaType.MEDIA_TYPE_MOVIE
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, noFormat.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTitle creates a Title and reports it as not-already-existing on first call`() = runBlocking {
        val admin = createAdminUser(username = "admin-addtitle-create")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.addTitle(addTitleRequest {
                tmdbId = 4242
                mediaType = MediaType.MEDIA_TYPE_MOVIE
                mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
            })
            assertTrue(resp.titleId > 0)
            assertTrue(resp.alreadyExisted == false)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTitle is idempotent - second call reuses the existing Title`() = runBlocking {
        val admin = createAdminUser(username = "admin-addtitle-idem")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val first = stub.addTitle(addTitleRequest {
                tmdbId = 5555
                mediaType = MediaType.MEDIA_TYPE_MOVIE
                mediaFormat = MediaFormat.MEDIA_FORMAT_BLURAY
            })
            val second = stub.addTitle(addTitleRequest {
                tmdbId = 5555
                mediaType = MediaType.MEDIA_TYPE_MOVIE
                mediaFormat = MediaFormat.MEDIA_FORMAT_UHD_BLURAY
            })
            assertEquals(first.titleId, second.titleId)
            assertTrue(second.alreadyExisted)
        } finally {
            authed.shutdownNow()
        }
    }
}
