package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.ExpansionStatus
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.PasswordService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Slices 8+9 of [AdminGrpcService] coverage — settings (getSettings,
 * updateSetting), resetPassword, audio-cache status / clear, and the
 * multi-pack expansion lifecycle.
 */
class AdminGrpcServiceSettingsAndExpansionsTest : GrpcTestBase() {

    // ---------------------- getSettings ----------------------

    @Test
    fun `getSettings returns every known SettingKey, masking sensitive values`() = runBlocking {
        val admin = createAdminUser(username = "settings-list")
        AppConfig(config_key = "nas_root_path",
            config_val = "/mnt/media").save()
        AppConfig(config_key = "keepa_api_key",
            config_val = "secret-key-content").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getSettings(Empty.getDefaultInstance())
            val byKey = resp.settingsList.associateBy { it.key }
            assertEquals("/mnt/media",
                byKey[SettingKey.SETTING_KEY_NAS_ROOT_PATH]?.value)
            assertEquals("••••••••",
                byKey[SettingKey.SETTING_KEY_KEEPA_API_KEY]?.value,
                "API key value masked in response")
            // Unset settings come back with empty string.
            assertEquals("",
                byKey[SettingKey.SETTING_KEY_FFMPEG_PATH]?.value)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- updateSetting ----------------------

    @Test
    fun `updateSetting persists a known SettingKey to AppConfig`() = runBlocking {
        val admin = createAdminUser(username = "setting-write")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateSetting(updateSettingRequest {
                key = SettingKey.SETTING_KEY_NAS_ROOT_PATH
                value = "/srv/media"
            })
            val saved = AppConfig.findAll()
                .single { it.config_key == "nas_root_path" }
            assertEquals("/srv/media", saved.config_val)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateSetting overwrites an existing AppConfig row`() = runBlocking {
        val admin = createAdminUser(username = "setting-overwrite")
        AppConfig(config_key = "ffmpeg_path",
            config_val = "/old/ffmpeg").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateSetting(updateSettingRequest {
                key = SettingKey.SETTING_KEY_FFMPEG_PATH
                value = "/new/ffmpeg"
            })
            val saved = AppConfig.findAll()
                .single { it.config_key == "ffmpeg_path" }
            assertEquals("/new/ffmpeg", saved.config_val)
            assertEquals(1, AppConfig.findAll().count { it.config_key == "ffmpeg_path" },
                "no duplicate inserted")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateSetting on a legal-requirements key refreshes the cache`() = runBlocking {
        val admin = createAdminUser(username = "setting-legal")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Confirm starting state — version 0.
            assertEquals(0, LegalRequirements.privacyPolicyVersion)

            stub.updateSetting(updateSettingRequest {
                key = SettingKey.SETTING_KEY_PRIVACY_POLICY_VERSION
                value = "5"
            })
            assertEquals(5, LegalRequirements.privacyPolicyVersion,
                "LegalRequirements should auto-refresh after a legal-key update")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateSetting rejects unknown SettingKey with INVALID_ARGUMENT`() = runBlocking {
        val admin = createAdminUser(username = "setting-unknown")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateSetting(updateSettingRequest {
                    key = SettingKey.SETTING_KEY_UNKNOWN
                    value = "anything"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- resetPassword ----------------------

    @Test
    fun `resetPassword updates the hash and toggles must_change_password`() = runBlocking {
        val admin = createAdminUser(username = "rp-admin")
        val target = createViewerUser(username = "rp-target",
            password = "Initial1234!@#\$")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val newPlaintext = "Reset1234!@#\$"
            stub.resetPassword(resetPasswordRequest {
                userId = target.id!!
                newPassword = newPlaintext
                forceChange = true
            })
            val refreshed = AppUser.findById(target.id!!)!!
            assertTrue(PasswordService.verify(newPlaintext, refreshed.password_hash))
            assertTrue(refreshed.must_change_password)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `resetPassword rejects weak new passwords`() = runBlocking {
        val admin = createAdminUser(username = "rp-weak-admin")
        val target = createViewerUser(username = "rp-weak-target")

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.resetPassword(resetPasswordRequest {
                    userId = target.id!!
                    newPassword = "abc"
                    forceChange = false
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `resetPassword returns NOT_FOUND for unknown user`() = runBlocking {
        val admin = createAdminUser(username = "rp-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.resetPassword(resetPasswordRequest {
                    userId = 999_999
                    newPassword = "Test1234!@#\$"
                    forceChange = false
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- audio transcode cache ----------------------

    @Test
    fun `getAudioTranscodeCacheStatus reports zero state when nothing is cached`() = runBlocking {
        val admin = createAdminUser(username = "audio-cache-status")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getAudioTranscodeCacheStatus(Empty.getDefaultInstance())
            // Cache hasn't been populated in this test JVM — totals should be 0.
            assertEquals(0, resp.entryCount)
            assertEquals(0L, resp.totalSizeBytes)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `clearAudioTranscodeCache returns Empty for both modes`() = runBlocking {
        val admin = createAdminUser(username = "audio-cache-clear")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            // Without track_id -> clearAll path.
            stub.clearAudioTranscodeCache(clearAudioTranscodeCacheRequest { })
            // With track_id -> clearForTrack path.
            stub.clearAudioTranscodeCache(clearAudioTranscodeCacheRequest {
                trackId = 12345
            })
        } finally {
            authed.shutdownNow()
        }
        Unit
    }

    // ---------------------- multi-pack expansion ----------------------

    private fun seedNeedsExpansionItem(productName: String = "Multi-Pack"): MediaItem =
        MediaItem(
            product_name = productName,
            expansion_status = ExpansionStatus.NEEDS_EXPANSION.name,
            title_count = 3
        ).apply { save() }

    @Test
    fun `listPendingExpansions returns items in NEEDS_EXPANSION state`() = runBlocking {
        val admin = createAdminUser(username = "exp-list")
        seedNeedsExpansionItem("PendingA")
        // SINGLE-status item should be excluded.
        MediaItem(product_name = "Single",
            expansion_status = ExpansionStatus.SINGLE.name).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listPendingExpansions(Empty.getDefaultInstance())
            assertEquals(1, resp.itemsCount)
            assertEquals("PendingA", resp.itemsList.single().productName)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getExpansionDetail returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "exp-detail-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.getExpansionDetail(mediaItemIdRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getExpansionDetail returns linked titles sorted by disc_number`() = runBlocking {
        val admin = createAdminUser(username = "exp-detail")
        val item = seedNeedsExpansionItem("WithTitles")
        val t1 = createTitle(name = "Disc 1")
        val t2 = createTitle(name = "Disc 2")
        // Insert disc 2 first to verify sort.
        MediaItemTitle(media_item_id = item.id!!,
            title_id = t2.id!!, disc_number = 2).save()
        MediaItemTitle(media_item_id = item.id!!,
            title_id = t1.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getExpansionDetail(mediaItemIdRequest {
                mediaItemId = item.id!!
            })
            assertEquals("WithTitles", resp.productName)
            assertEquals(listOf(1, 2),
                resp.linkedTitlesList.map { it.discNumber })
            assertEquals(listOf("Disc 1", "Disc 2"),
                resp.linkedTitlesList.map { it.name })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `removeTitleFromExpansion drops the join row and deletes orphan placeholder titles`() = runBlocking {
        val admin = createAdminUser(username = "exp-remove")
        val item = seedNeedsExpansionItem()
        // Placeholder title with no tmdb_id — will be deleted on unlink if no
        // other links remain.
        val placeholder = createTitle(name = "Placeholder").apply {
            tmdb_id = null; save()
        }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = placeholder.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.removeTitleFromExpansion(removeTitleFromExpansionRequest {
                mediaItemId = item.id!!
                titleId = placeholder.id!!
            })
            assertEquals(0, MediaItemTitle.findAll().count {
                it.media_item_id == item.id
            })
            assertNull(Title.findById(placeholder.id!!),
                "orphan placeholder title (no tmdb_id, no other links) deleted")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `removeTitleFromExpansion preserves titles with tmdb_id even when orphaned`() = runBlocking {
        val admin = createAdminUser(username = "exp-keep-tmdb")
        val item = seedNeedsExpansionItem()
        val real = createTitle(name = "Real Title").apply {
            tmdb_id = 12345; save()
        }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = real.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.removeTitleFromExpansion(removeTitleFromExpansionRequest {
                mediaItemId = item.id!!
                titleId = real.id!!
            })
            // Title with tmdb_id is preserved even with no remaining links.
            assertTrue(Title.findById(real.id!!) != null)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `removeTitleFromExpansion returns NOT_FOUND when the title isn't linked`() = runBlocking {
        val admin = createAdminUser(username = "exp-remove-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.removeTitleFromExpansion(removeTitleFromExpansionRequest {
                    mediaItemId = 999_999
                    titleId = 888_888
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `markExpanded flips status to EXPANDED and retires SKIPPED placeholder titles`() = runBlocking {
        val admin = createAdminUser(username = "exp-markexp")
        val item = seedNeedsExpansionItem()
        // One real title (tmdb_id set) + one placeholder (skipped, no tmdb_id).
        val real = createTitle(name = "Real").apply { tmdb_id = 5; save() }
        val placeholder = createTitle(name = "Placeholder",
            enrichmentStatus = EnrichmentStatus.SKIPPED.name).apply {
            tmdb_id = null; save()
        }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = real.id!!, disc_number = 1).save()
        MediaItemTitle(media_item_id = item.id!!,
            title_id = placeholder.id!!, disc_number = 2).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.markExpanded(mediaItemIdRequest { mediaItemId = item.id!! })

            val refreshed = MediaItem.findById(item.id!!)!!
            assertEquals(ExpansionStatus.EXPANDED.name, refreshed.expansion_status)
            assertEquals(1, refreshed.title_count, "only the real title remains")
            assertNull(Title.findById(placeholder.id!!),
                "SKIPPED placeholder retired and deleted")
            assertTrue(Title.findById(real.id!!) != null,
                "real title preserved")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `markNotMultiPack resets status to SINGLE and re-enriches placeholders`() = runBlocking {
        val admin = createAdminUser(username = "exp-marksingle")
        val item = seedNeedsExpansionItem()
        val placeholder = createTitle(name = "Placeholder",
            enrichmentStatus = EnrichmentStatus.SKIPPED.name).apply {
            tmdb_id = null; save()
        }
        MediaItemTitle(media_item_id = item.id!!,
            title_id = placeholder.id!!, disc_number = 1).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.markNotMultiPack(mediaItemIdRequest { mediaItemId = item.id!! })

            val refreshedItem = MediaItem.findById(item.id!!)!!
            assertEquals(ExpansionStatus.SINGLE.name, refreshedItem.expansion_status)
            assertEquals(1, refreshedItem.title_count)

            // Placeholder bumped from SKIPPED to PENDING for re-enrichment.
            val refreshedTitle = Title.findById(placeholder.id!!)!!
            assertEquals(EnrichmentStatus.PENDING.name,
                refreshedTitle.enrichment_status)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `markExpanded and markNotMultiPack both NOT_FOUND on unknown id`() = runBlocking {
        val admin = createAdminUser(username = "exp-mark-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val mxe = assertFailsWith<StatusException> {
                stub.markExpanded(mediaItemIdRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, mxe.status.code)

            val mne = assertFailsWith<StatusException> {
                stub.markNotMultiPack(mediaItemIdRequest { mediaItemId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, mne.status.code)
        } finally {
            authed.shutdownNow()
        }
    }
}
