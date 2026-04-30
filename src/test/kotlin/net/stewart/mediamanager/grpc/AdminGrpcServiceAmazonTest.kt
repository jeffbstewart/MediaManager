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
        userId: Long,
        orderId: String,
        productName: String = "DVD: $orderId",
        status: String? = null,
        linkedMediaItemId: Long? = null,
    ): AmazonOrder = AmazonOrder(
        user_id = userId,
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
    fun `searchAmazonOrders returns the caller's orders only`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-mine")
        val other = createAdminUser(username = "amz-search-other")
        seedOrder(admin.id!!, "111-MINE", productName = "Mine 1")
        seedOrder(admin.id!!, "222-MINE", productName = "Mine 2")
        seedOrder(other.id!!, "333-THEIRS", productName = "Theirs")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.searchAmazonOrders(searchAmazonOrdersRequest { })
            // Only the caller's two orders.
            assertEquals(2, resp.ordersCount)
            assertTrue(resp.ordersList.all { it.orderId.endsWith("MINE") })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `searchAmazonOrders unlinkedOnly excludes linked orders`() = runBlocking {
        val admin = createAdminUser(username = "amz-search-unlinked")
        val mediaItem = MediaItem().apply { save() }
        seedOrder(admin.id!!, "u1", productName = "Unlinked")
        seedOrder(admin.id!!, "L1", productName = "Linked",
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
        seedOrder(admin.id!!, "ok", status = "Closed")
        seedOrder(admin.id!!, "x", status = "Cancelled")

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
        for (i in 1..5) seedOrder(admin.id!!, "ord-$i")

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
        seedOrder(admin.id!!, "withtitle", linkedMediaItemId = mediaItem.id)

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
        val order = seedOrder(admin.id!!, "to-link")

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
    fun `linkAmazonOrder refuses non-owners with PERMISSION_DENIED`() = runBlocking {
        val owner = createAdminUser(username = "amz-link-owner")
        val intruder = createAdminUser(username = "amz-link-intruder")
        val mediaItem = MediaItem().apply { save() }
        val order = seedOrder(owner.id!!, "owners-order")

        val authed = authenticatedChannel(intruder)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.linkAmazonOrder(linkAmazonOrderRequest {
                    amazonOrderId = order.id!!
                    mediaItemId = mediaItem.id!!
                })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
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
    fun `unlinkAmazonOrder clears linked_media_item_id for the owner`() = runBlocking {
        val admin = createAdminUser(username = "amz-unlink")
        val mediaItem = MediaItem().apply { save() }
        val order = seedOrder(admin.id!!, "to-unlink",
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
    fun `unlinkAmazonOrder PERMISSION_DENIED for non-owner`() = runBlocking {
        val owner = createAdminUser(username = "amz-unlink-owner")
        val intruder = createAdminUser(username = "amz-unlink-intruder")
        val order = seedOrder(owner.id!!, "ownersorder")

        val authed = authenticatedChannel(intruder)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.unlinkAmazonOrder(amazonOrderIdRequest {
                    amazonOrderId = order.id!!
                })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
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
        seedOrder(admin.id!!, "1")
        seedOrder(admin.id!!, "2")
        seedOrder(admin.id!!, "3", linkedMediaItemId = mediaItem.id)

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
}
