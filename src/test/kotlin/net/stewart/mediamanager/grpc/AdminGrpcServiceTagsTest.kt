package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the admin tag-management RPCs of
 * [AdminGrpcService] plus the read-only status RPCs that don't
 * touch the file system. The full AdminGrpcService surface is
 * 113 RPCs; this is the first slice (tag CRUD + transcode-status
 * snapshots), with subsequent slices to follow.
 */
class AdminGrpcServiceTagsTest : GrpcTestBase() {

    // ---------------------- AuthInterceptor admin gate ----------------------

    @Test
    fun `non-admin viewers are rejected at the AdminService interceptor`() = runBlocking {
        val viewer = createViewerUser(username = "admin-blocked")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.listTags(Empty.getDefaultInstance())
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code,
                "AuthInterceptor.ADMIN_SERVICE_PREFIX must reject non-admins")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `unauthenticated callers are rejected at the auth interceptor`() = runBlocking {
        val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.listTags(Empty.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    // ---------------------- listTags ----------------------

    @Test
    fun `listTags surfaces every tag with its title-link count`() = runBlocking {
        val admin = createAdminUser(username = "admin-list-tags")
        val sci = Tag(name = "Sci-Fi", bg_color = "#1E40AF").apply { save() }
        val noir = Tag(name = "Noir", bg_color = "#1F2937").apply { save() }
        val title = createTitle(name = "Inception")
        TitleTag(title_id = title.id!!, tag_id = sci.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listTags(Empty.getDefaultInstance())
            val byName = resp.tagsList.associateBy { it.name }
            assertEquals(2, byName.size)
            assertEquals(1, byName["Sci-Fi"]?.titleCount, "Sci-Fi linked to one title")
            assertEquals(0, byName["Noir"]?.titleCount, "Noir has no titles yet")
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- createTag ----------------------

    @Test
    fun `createTag returns CREATED with the new id on success`() = runBlocking {
        val admin = createAdminUser(username = "admin-create-tag")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createTag(createTagRequest {
                name = "Comedy"
                color = color { hex ="#F59E0B" }
            })
            assertEquals(CreateTagResult.CREATE_TAG_RESULT_CREATED, resp.result)
            assertTrue(resp.id > 0)
            assertEquals("Comedy", Tag.findById(resp.id)!!.name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createTag returns DUPLICATE with the existing id on conflict`() = runBlocking {
        val admin = createAdminUser(username = "admin-create-dup")
        val existing = Tag(name = "Drama", bg_color = "#7C3AED").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createTag(createTagRequest {
                name = "drama" // case-insensitive collision
                color = color { hex ="#000000" }
            })
            assertEquals(CreateTagResult.CREATE_TAG_RESULT_DUPLICATE, resp.result)
            assertEquals(existing.id!!, resp.id)
            // Color should NOT be updated to black — duplicate path bails out
            // before persisting the color choice.
            assertEquals("#7C3AED", Tag.findById(existing.id!!)!!.bg_color)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createTag rejects blank or whitespace-only names`() = runBlocking {
        val admin = createAdminUser(username = "admin-create-blank")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)

            val ex1 = assertFailsWith<StatusException> {
                stub.createTag(createTagRequest {
                    name = ""
                    color = color { hex ="#FFFFFF" }
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex1.status.code)

            val ex2 = assertFailsWith<StatusException> {
                stub.createTag(createTagRequest {
                    name = "   "
                    color = color { hex ="#FFFFFF" }
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex2.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateTag ----------------------

    @Test
    fun `updateTag mutates name and color in place`() = runBlocking {
        val admin = createAdminUser(username = "admin-update-tag")
        val tag = Tag(name = "Action", bg_color = "#DC2626").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateTag(updateTagRequest {
                tagId = tag.id!!
                name = "High-Octane Action"
                color = color { hex ="#0F172A" }
            })
            val refreshed = Tag.findById(tag.id!!)!!
            assertEquals("High-Octane Action", refreshed.name)
            assertEquals("#0F172A", refreshed.bg_color)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTag with only name preserves the original color`() = runBlocking {
        val admin = createAdminUser(username = "admin-update-name")
        val tag = Tag(name = "Old Name", bg_color = "#10B981").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateTag(updateTagRequest {
                tagId = tag.id!!
                name = "New Name"
                // No color set — keeps old.
            })
            val refreshed = Tag.findById(tag.id!!)!!
            assertEquals("New Name", refreshed.name)
            assertEquals("#10B981", refreshed.bg_color)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTag returns NOT_FOUND for an unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateTag(updateTagRequest {
                    tagId = 999_999
                    name = "Anything"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTag with blank name is INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "admin-update-blank")
        val tag = Tag(name = "Valid").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateTag(updateTagRequest {
                    tagId = tag.id!!
                    name = "   "
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTag rejects renaming to an existing tag's name`() = runBlocking {
        val admin = createAdminUser(username = "admin-update-clash")
        val a = Tag(name = "Alpha").apply { save() }
        val b = Tag(name = "Beta").apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateTag(updateTagRequest {
                    tagId = b.id!!
                    name = "Alpha" // collides with `a`
                })
            }
            assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deleteTag ----------------------

    @Test
    fun `deleteTag removes the row and its title-tag links`() = runBlocking {
        val admin = createAdminUser(username = "admin-delete-tag")
        val tag = Tag(name = "Doomed").apply { save() }
        val title = createTitle(name = "TT")
        TitleTag(title_id = title.id!!, tag_id = tag.id!!).save()
        assertEquals(1, TitleTag.findAll().size)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteTag(tagIdRequest { tagId = tag.id!! })
            assertNull(Tag.findById(tag.id!!))
            assertEquals(0, TitleTag.findAll().size,
                "title_tag rows must cascade with the deleted tag")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteTag returns NOT_FOUND for an unknown id`() = runBlocking {
        val admin = createAdminUser(username = "admin-delete-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteTag(tagIdRequest { tagId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- read-only status RPCs ----------------------

    @Test
    fun `getTranscodeStatus returns zero counts when nothing is queued`() = runBlocking {
        val admin = createAdminUser(username = "admin-status-empty")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getTranscodeStatus(Empty.getDefaultInstance())
            assertEquals(0, resp.pendingTranscode)
            assertEquals(0, resp.pendingThumbnails)
            assertEquals(0, resp.pendingSubtitles)
            assertEquals(0, resp.pendingChapters)
            assertEquals(0, resp.pendingLowStorage)
            assertEquals(0, resp.poisonPills)
            assertEquals(0, resp.activeLeasesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getBuddyStatus returns empty buddies and recent leases on a clean DB`() = runBlocking {
        val admin = createAdminUser(username = "admin-buddy-empty")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getBuddyStatus(Empty.getDefaultInstance())
            assertEquals(0, resp.buddiesCount)
            assertEquals(0, resp.recentLeasesCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `clearFailures returns zero when there are no failed leases`() = runBlocking {
        val admin = createAdminUser(username = "admin-clear-failures")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.clearFailures(Empty.getDefaultInstance())
            assertEquals(0, resp.cleared)
        } finally {
            authed.shutdownNow()
        }
    }
}
