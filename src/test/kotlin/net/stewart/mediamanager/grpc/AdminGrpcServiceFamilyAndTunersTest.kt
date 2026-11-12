package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.TitleFamilyMember
import org.junit.Before
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 6 + 7 of [AdminGrpcService] coverage — family-member CRUD,
 * Live TV settings, tuner / channel admin RPCs.
 */
class AdminGrpcServiceFamilyAndTunersTest : GrpcTestBase() {

    @Before
    fun cleanLiveTables() {
        TitleFamilyMember.deleteAll()
        FamilyMember.deleteAll()
        LiveTvChannel.deleteAll()
        LiveTvTuner.deleteAll()
    }

    // ---------------------- family members ----------------------

    @Test
    fun `listFamilyMembers returns sorted by name with video_count`() = runBlocking {
        val admin = createAdminUser(username = "fam-list")
        val grandma = FamilyMember(name = "Grandma",
            created_at = LocalDateTime.now()).apply { save() }
        FamilyMember(name = "Alice", created_at = LocalDateTime.now()).save()

        // Create one TitleFamilyMember row for Grandma to bump video_count.
        val title = createTitle(name = "PersonalVid",
            mediaType = MediaTypeEntity.PERSONAL.name)
        TitleFamilyMember(title_id = title.id!!,
            family_member_id = grandma.id!!).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listFamilyMembers(Empty.getDefaultInstance())
            assertEquals(listOf("Alice", "Grandma"), resp.membersList.map { it.name })
            val grandmaResp = resp.membersList.single { it.name == "Grandma" }
            assertEquals(1, grandmaResp.videoCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createFamilyMember rejects blank name and over-100-char name`() = runBlocking {
        val admin = createAdminUser(username = "fam-create-bad")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)

            val blank = assertFailsWith<StatusException> {
                stub.createFamilyMember(createFamilyMemberRequest { name = "  " })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, blank.status.code)

            val tooLong = assertFailsWith<StatusException> {
                stub.createFamilyMember(createFamilyMemberRequest {
                    name = "x".repeat(101)
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, tooLong.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createFamilyMember rejects duplicate name case-insensitively`() = runBlocking {
        val admin = createAdminUser(username = "fam-create-dup")
        FamilyMember(name = "Bob", created_at = LocalDateTime.now()).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.createFamilyMember(createFamilyMemberRequest { name = "BOB" })
            }
            assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createFamilyMember persists with optional fields including parsed birth_date`() = runBlocking {
        val admin = createAdminUser(username = "fam-create-ok")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createFamilyMember(createFamilyMemberRequest {
                name = "Charlie"
                birthDate = "1990-05-15"
                notes = "Brother-in-law"
            })
            val saved = FamilyMember.findById(resp.id)!!
            assertEquals("Charlie", saved.name)
            assertEquals(LocalDate.of(1990, 5, 15), saved.birth_date)
            assertEquals("Brother-in-law", saved.notes)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createFamilyMember tolerates malformed birth_date by ignoring it`() = runBlocking {
        val admin = createAdminUser(username = "fam-create-baddate")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.createFamilyMember(createFamilyMemberRequest {
                name = "Dana"
                birthDate = "not a date"
            })
            assertNull(FamilyMember.findById(resp.id)!!.birth_date)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateFamilyMember mutates name and notes, NOT_FOUND on unknown id`() = runBlocking {
        val admin = createAdminUser(username = "fam-update")
        val member = FamilyMember(name = "Old", notes = "old notes",
            created_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateFamilyMember(updateFamilyMemberRequest {
                familyMemberId = member.id!!
                name = "New"
                notes = "new notes"
            })
            val refreshed = FamilyMember.findById(member.id!!)!!
            assertEquals("New", refreshed.name)
            assertEquals("new notes", refreshed.notes)

            val ex = assertFailsWith<StatusException> {
                stub.updateFamilyMember(updateFamilyMemberRequest {
                    familyMemberId = 999_999
                    name = "x"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateFamilyMember rejects renaming to an existing name`() = runBlocking {
        val admin = createAdminUser(username = "fam-update-clash")
        val a = FamilyMember(name = "AlphaName",
            created_at = LocalDateTime.now()).apply { save() }
        val b = FamilyMember(name = "BetaName",
            created_at = LocalDateTime.now()).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateFamilyMember(updateFamilyMemberRequest {
                    familyMemberId = b.id!!
                    name = "AlphaName" // collides with `a`
                })
            }
            assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteFamilyMember removes the row and cascades title links`() = runBlocking {
        val admin = createAdminUser(username = "fam-delete")
        val member = FamilyMember(name = "Doomed",
            created_at = LocalDateTime.now()).apply { save() }
        val title = createTitle(name = "ATitle",
            mediaType = MediaTypeEntity.PERSONAL.name)
        TitleFamilyMember(title_id = title.id!!,
            family_member_id = member.id!!).save()
        assertEquals(1, TitleFamilyMember.findAll().size)

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteFamilyMember(familyMemberIdRequest {
                familyMemberId = member.id!!
            })
            assertNull(FamilyMember.findById(member.id!!))
            assertEquals(0, TitleFamilyMember.findAll().size,
                "title_family_member rows cascade with the deleted member")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteFamilyMember returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "fam-delete-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.deleteFamilyMember(familyMemberIdRequest {
                    familyMemberId = 999_999
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- live TV settings ----------------------

    @Test
    fun `getLiveTvSettings returns defaults when no AppConfig is set`() = runBlocking {
        val admin = createAdminUser(username = "tv-settings-default")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getLiveTvSettings(Empty.getDefaultInstance())
            assertEquals(4, resp.maxStreams, "default maxStreams = 4")
            assertEquals(60, resp.idleTimeoutSeconds, "default idleTimeout = 60s")
            assertEquals(0, resp.activeTunerCount)
            assertEquals(0, resp.activeStreamCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `getLiveTvSettings reflects configured values`() = runBlocking {
        val admin = createAdminUser(username = "tv-settings-set")
        AppConfig(config_key = "live_tv_min_rating", config_val = "TV-PG").save()
        AppConfig(config_key = "live_tv_max_streams", config_val = "8").save()
        AppConfig(config_key = "live_tv_idle_timeout_seconds", config_val = "300").save()
        LiveTvTuner(name = "T1", ip_address = "203.0.113.50",
            enabled = true).save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.getLiveTvSettings(Empty.getDefaultInstance())
            assertEquals("TV-PG", resp.minContentRating)
            assertEquals(8, resp.maxStreams)
            assertEquals(300, resp.idleTimeoutSeconds)
            assertEquals(1, resp.activeTunerCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateLiveTvSettings persists fields when present`() = runBlocking {
        val admin = createAdminUser(username = "tv-settings-update")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateLiveTvSettings(updateLiveTvSettingsRequest {
                minContentRating = "PG"
                maxStreams = 6
                idleTimeoutSeconds = 120
            })
            val configs = AppConfig.findAll().associateBy { it.config_key }
            assertEquals("PG", configs["live_tv_min_rating"]?.config_val)
            assertEquals("6", configs["live_tv_max_streams"]?.config_val)
            assertEquals("120", configs["live_tv_idle_timeout_seconds"]?.config_val)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- tuners ----------------------

    @Test
    fun `listTuners returns each tuner with its channel count`() = runBlocking {
        val admin = createAdminUser(username = "tuner-list")
        val t1 = LiveTvTuner(name = "Living Room",
            ip_address = "203.0.113.50",
            enabled = true).apply { save() }
        LiveTvChannel(tuner_id = t1.id!!, guide_number = "1.1",
            guide_name = "C1", stream_url = "u1").save()
        LiveTvChannel(tuner_id = t1.id!!, guide_number = "2.1",
            guide_name = "C2", stream_url = "u2").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listTuners(Empty.getDefaultInstance())
            assertEquals(1, resp.tunersCount)
            assertEquals(2, resp.tunersList.single().channelCount)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTuner validates IP format and persists`() = runBlocking {
        val admin = createAdminUser(username = "tuner-add")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)

            val blank = assertFailsWith<StatusException> {
                stub.addTuner(addTunerRequest { ipAddress = "   " })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, blank.status.code)

            val nonIp = assertFailsWith<StatusException> {
                stub.addTuner(addTunerRequest { ipAddress = "not.an.ip" })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, nonIp.status.code)

            val resp = stub.addTuner(addTunerRequest {
                ipAddress = "203.0.113.50"
                name = "Bedroom"
            })
            assertTrue(resp.id > 0)
            assertEquals("Bedroom", resp.name)
            assertEquals("203.0.113.50", resp.ipAddress)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `addTuner without explicit name defaults to HDHomeRun`() = runBlocking {
        val admin = createAdminUser(username = "tuner-add-default")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.addTuner(addTunerRequest {
                ipAddress = "203.0.113.50"
            })
            assertEquals("HDHomeRun", resp.name)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateTuner mutates name and enabled, NOT_FOUND on unknown id`() = runBlocking {
        val admin = createAdminUser(username = "tuner-update")
        val tuner = LiveTvTuner(name = "Old", ip_address = "203.0.113.50",
            enabled = true).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateTuner(updateTunerRequest {
                tunerId = tuner.id!!
                name = "New"
                enabled = false
            })
            val refreshed = LiveTvTuner.findById(tuner.id!!)!!
            assertEquals("New", refreshed.name)
            assertEquals(false, refreshed.enabled)

            val ex = assertFailsWith<StatusException> {
                stub.updateTuner(updateTunerRequest {
                    tunerId = 999_999
                    name = "x"
                    enabled = false
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deleteTuner cascades channels and is a no-op for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "tuner-delete")
        val tuner = LiveTvTuner(name = "Doomed", ip_address = "203.0.113.50",
            enabled = true).apply { save() }
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "1.1",
            guide_name = "C", stream_url = "u").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.deleteTuner(tunerIdRequest { tunerId = tuner.id!! })
            assertNull(LiveTvTuner.findById(tuner.id!!))
            assertEquals(0, LiveTvChannel.findAll().count { it.tuner_id == tuner.id })

            // Unknown id is a silent no-op (no exception).
            stub.deleteTuner(tunerIdRequest { tunerId = 999_999 })
        } finally {
            authed.shutdownNow()
        }
        Unit
    }

    @Test
    fun `refreshTunerChannels returns NOT_FOUND for unknown tuner id`() = runBlocking {
        val admin = createAdminUser(username = "tuner-refresh-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.refreshTunerChannels(tunerIdRequest { tunerId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- channels ----------------------

    @Test
    fun `listAdminChannels filters by tuner_id and sorts by display_order then guide_number`() = runBlocking {
        val admin = createAdminUser(username = "ch-list")
        val tuner = LiveTvTuner(name = "T", ip_address = "203.0.113.50",
            enabled = true).apply { save() }
        // Same display_order=0 — guide_number tiebreaker chooses 1.1 before 9.1.
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "9.1",
            guide_name = "Nine", stream_url = "u1",
            display_order = 0).save()
        LiveTvChannel(tuner_id = tuner.id!!, guide_number = "1.1",
            guide_name = "One", stream_url = "u2",
            display_order = 0).save()
        // Different tuner — must NOT appear.
        val tuner2 = LiveTvTuner(name = "T2", ip_address = "203.0.113.50",
            enabled = true).apply { save() }
        LiveTvChannel(tuner_id = tuner2.id!!, guide_number = "5.1",
            guide_name = "OtherTuner", stream_url = "u3").save()

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val resp = stub.listAdminChannels(tunerIdRequest { tunerId = tuner.id!! })
            assertEquals(2, resp.channelsCount)
            assertEquals(listOf("One", "Nine"),
                resp.channelsList.map { it.guideName },
                "guide_number tiebreaker on tied display_order")
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateChannel mutates network_affiliation, reception_quality, and enabled`() = runBlocking {
        val admin = createAdminUser(username = "ch-update")
        val tuner = LiveTvTuner(name = "T", ip_address = "203.0.113.50",
            enabled = true).apply { save() }
        val ch = LiveTvChannel(tuner_id = tuner.id!!, guide_number = "1.1",
            guide_name = "C", stream_url = "u",
            reception_quality = 1, enabled = true).apply { save() }

        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            stub.updateChannel(updateChannelRequest {
                channelId = ch.id!!
                networkAffiliation = "CBS"
                receptionQuality = 5
                enabled = false
            })
            val refreshed = LiveTvChannel.findById(ch.id!!)!!
            assertEquals("CBS", refreshed.network_affiliation)
            assertEquals(5, refreshed.reception_quality)
            assertEquals(false, refreshed.enabled)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `updateChannel returns NOT_FOUND for unknown id`() = runBlocking {
        val admin = createAdminUser(username = "ch-update-404")
        val authed = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.updateChannel(updateChannelRequest {
                    channelId = 999_999
                    receptionQuality = 1
                    enabled = true
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }
}
