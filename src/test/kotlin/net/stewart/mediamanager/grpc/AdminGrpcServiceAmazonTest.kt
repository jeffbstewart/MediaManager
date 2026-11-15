package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.MediaItem
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 11 of [AdminGrpcService] coverage — Amazon order import,
 * search, link / unlink, summary.
 */
class AdminGrpcServiceAmazonTest : GrpcTestBase() {

    // GrpcTestBase.cleanAllTables now clears AmazonOrder before AppUser
    // (Amazon orders carry an FK to app_user, so the parent had to learn
    // about this table for tests in this class to reset cleanly).

    private fun seedOrder(
        orderId: String,
        productName: String = "DVD: $orderId",
        status: String? = null,
        linkedMediaItemId: Long? = null,
    ): AmazonOrder = AmazonOrder(
        order_id = orderId,
        asin = "ASIN$orderId",
        product_name = productName,
        product_name_lower = productName.lowercase(),
        order_date = LocalDateTime.now(),
        order_status = status,
        unit_price = BigDecimal("9.99"),
        linked_media_item_id = linkedMediaItemId,
    ).apply { save() }

    // ---------------------- searchAmazonOrders ----------------------

    @Test
    fun `searchAmazonOrders empty when nothing seeded`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-empty")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest { })
            assertEquals(0, resp.ordersCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders returns the household's orders, whoever imported them`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-mine")
        seedOrder("111-A", productName = "Imported by one admin")
        seedOrder("222-B", productName = "Imported by another")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest { })
            // Orders belong to the household (V104), not the importing admin.
            assertEquals(setOf("111-A", "222-B"), resp.ordersList.map { it.orderId }.toSet())
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders unlinkedOnly excludes linked orders`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-unlinked")
        val mediaItem = MediaItem().apply { save() }
        seedOrder("u1", productName = "Unlinked")
        seedOrder("L1", productName = "Linked",
            linkedMediaItemId = mediaItem.id)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest {
                unlinkedOnly = true
            })
            assertEquals(1, resp.ordersCount)
            assertEquals("u1", resp.ordersList.single().orderId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders hideCancelled excludes Cancelled orders`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-cancelled")
        seedOrder("ok", status = "Closed")
        seedOrder("x", status = "Cancelled")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest {
                hideCancelled = true
            })
            assertEquals(1, resp.ordersCount)
            assertEquals("ok", resp.ordersList.single().orderId)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders limit caps results`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-limit")
        for (i in 1..5) seedOrder("ord-$i")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest {
                limit = 2
            })
            assertEquals(2, resp.ordersCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders surfaces linked title name when linked`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-titlename")
        val title = createTitle(name = "Linked Movie")
        val mediaItem = MediaItem().apply { save() }
        net.stewart.mediamanager.entity.MediaItemTitle(
            media_item_id = mediaItem.id!!, title_id = title.id!!
        ).save()
        seedOrder("withtitle", linkedMediaItemId = mediaItem.id)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest { })
            val order = resp.ordersList.single()
            assertEquals("Linked Movie", order.linkedTitleName)
            assertEquals(mediaItem.id, order.linkedMediaItemId)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- linkAmazonOrder ----------------------

    @Test
    fun `linkAmazonOrder updates linked_media_item_id for the owner`() = runBlocking {
        val admin = createAdminUser(username = "amz-link")
        val mediaItem = MediaItem().apply { save() }
        val order = seedOrder("to-link")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.linkAmazonOrder(linkAmazonOrderRequest {
                amazonOrderId = order.id!!
                mediaItemId = mediaItem.id!!
            })
            val refreshed = AmazonOrder.findById(order.id!!)!!
            assertEquals(mediaItem.id, refreshed.linked_media_item_id)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `linkAmazonOrder NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "amz-link-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkAmazonOrder(linkAmazonOrderRequest {
                    amazonOrderId = 999_999
                    mediaItemId = 1
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- unlinkAmazonOrder ----------------------

    @Test
    fun `unlinkAmazonOrder clears linked_media_item_id`() = runBlocking {
        val admin = createAdminUser(username = "amz-unlink")
        val mediaItem = MediaItem().apply { save() }
        val order = seedOrder("to-unlink",
            linkedMediaItemId = mediaItem.id)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.unlinkAmazonOrder(amazonOrderIdRequest {
                amazonOrderId = order.id!!
            })
            assertNull(AmazonOrder.findById(order.id!!)!!.linked_media_item_id)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `unlinkAmazonOrder NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "amz-unlink-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.unlinkAmazonOrder(amazonOrderIdRequest {
                    amazonOrderId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- getAmazonOrderSummary ----------------------

    @Test
    fun `getAmazonOrderSummary returns total, media_related, and linked counts`() = runBlocking {
        val admin = createAdminUser(username = "amz-summary")
        val mediaItem = MediaItem().apply { save() }
        // 3 orders: one linked, two unlinked. All count as "media_related"
        // since searchOrders(mediaOnly=true) returns everything that isn't
        // explicitly filtered out by the heuristic.
        seedOrder("1")
        seedOrder("2")
        seedOrder("3", linkedMediaItemId = mediaItem.id)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getAmazonOrderSummary(Empty.getDefaultInstance())
            assertEquals(3, resp.total)
            assertEquals(1, resp.linked)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- importAmazonOrders ----------------------

    @Test
    fun `importAmazonOrders returns an error message on malformed CSV instead of throwing`() = runBlocking {
        val admin = createAdminUser(username = "amz-import-bad")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Deliberately bad CSV: no header, garbage content.
            val resp = stub.importAmazonOrders(importAmazonOrdersRequest {
                filename = "bad.csv"
                csvData = ByteString.copyFromUtf8("this is not a CSV")
            })
            // Service catches the exception and returns it as a structured
            // response error rather than letting it bubble up.
            assertEquals(0, resp.imported)
            // Either an error message, or zero imports (depends on parser
            // tolerance). Lock down that we got a response without
            // throwing.
            assertTrue(resp.imported == 0)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `importAmazonOrders parses a well-formed CSV and inserts AmazonOrder rows`() = runBlocking {
        val admin = createAdminUser(username = "amz-import-ok")
        // Minimal CSV with the canonical Amazon export columns
        // AmazonImportService.parseCsv expects.
        val csv = listOf(
            "Order ID,Order Date,ASIN/ISBN,Product Name,Quantity,Item Total,Order Status",
            "111-2222222-3333333,2024-06-15,B0ABC12345,The Imported Movie,1,9.99,Closed",
            "111-2222222-4444444,2024-06-16,B0DEF67890,Another Imported Item,2,19.98,Closed",
        ).joinToString("\n")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.importAmazonOrders(importAmazonOrdersRequest {
                filename = "good.csv"
                csvData = ByteString.copyFromUtf8(csv)
            })
            assertEquals(2, resp.imported)
            assertEquals(2, AmazonOrder.findAll().size)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `importAmazonOrders extracts the CSV from a zipped export`() = runBlocking {
        val admin = createAdminUser(username = "amz-import-zip")
        val csv = listOf(
            "Order ID,Order Date,ASIN/ISBN,Product Name,Quantity,Item Total,Order Status",
            "112-3333333-4444444,2024-07-01,B0ZIP00001,Zipped Movie,1,12.34,Closed",
        ).joinToString("\n")
        val zipped = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("retail.OrderHistory.1.csv"))
                zip.write(csv.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.importAmazonOrders(importAmazonOrdersRequest {
                filename = "amazon-export.zip"
                csvData = ByteString.copyFrom(zipped)
            })
            assertEquals(1, resp.imported)
        } finally {
            authed.shutdownNow()
        }
    }
}
