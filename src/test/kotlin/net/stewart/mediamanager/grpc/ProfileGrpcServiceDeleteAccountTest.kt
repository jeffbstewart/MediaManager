package net.stewart.mediamanager.grpc

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.DismissedNotification
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.ReadingProgress
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.RefreshResult
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ProfileService/DeleteAccount: a user deleting their own account
 * (App Store Review Guideline 5.1.1(v)).
 */
class ProfileGrpcServiceDeleteAccountTest : GrpcTestBase() {

    private fun deleteAccount(user: AppUser, password: String = DEFAULT_PASSWORD, platform: String? = null) {
        val channel = authenticatedChannel(user, platform = platform)
        try {
            runBlocking {
                ProfileServiceGrpcKt.ProfileServiceCoroutineStub(channel)
                    .deleteAccount(deleteAccountRequest { currentPassword = password })
            }
        } finally {
            channel.shutdownNow()
        }
    }

    /** Rows in every table that has a foreign key to app_user, for [userId]. */
    private fun rowsReferencing(userId: Long): Map<String, Int> =
        JdbiOrm.jdbi().withHandle<Map<String, Int>, Exception> { h ->
            val fks = h.createQuery(
                """SELECT kcu.TABLE_NAME, kcu.COLUMN_NAME
                     FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS pk
                       ON pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
                      AND pk.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA
                     JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                       ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                      AND kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
                    WHERE pk.TABLE_NAME = 'APP_USER' AND rc.CONSTRAINT_SCHEMA = SCHEMA()"""
            ).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            fks.associate { (table, column) ->
                "$table.$column" to h.createQuery("SELECT COUNT(*) FROM \"$table\" WHERE \"$column\" = :id")
                    .bind("id", userId).mapTo(Int::class.java).one()
            }
        }

    @Test
    fun `deletes the account and everything the user owns`() {
        val keeper = createAdminUser(username = "da-keeper")
        val user = createViewerUser(username = "da-leaver")
        val uid = user.id!!
        val title = createTitle(name = "Leaver's Movie")
        val transcode = createTranscode(title.id!!)
        val item = MediaItem().apply { save() }
        val now = LocalDateTime.now()

        WishListItem(user_id = uid, tmdb_id = 1, tmdb_title = "Wish", tmdb_media_type = "MOVIE",
            created_at = now).save()
        PlaybackProgress(user_id = uid, transcode_id = transcode.id!!, position_seconds = 12.0,
            updated_at = now).save()
        ReadingProgress(user_id = uid, media_item_id = item.id!!, cfi = "/page/3", updated_at = now).save()
        UserTitleFlag(user_id = uid, title_id = title.id!!, created_at = now).save()
        DismissedNotification(user_id = uid, notification_key = "k", dismissed_at = now).save()
        val playlist = Playlist(name = "Public mix", owner_user_id = uid, is_private = false,
            created_at = now, updated_at = now).apply { save() }
        AuthService.createSession(user, "ua")
        DeviceToken(user_id = uid, token_hash = "da-device", device_name = "Roku").save()

        assertTrue(rowsReferencing(uid).values.sum() > 0)

        deleteAccount(user)

        assertNull(AppUser.findById(uid))
        assertEquals(emptyMap(), rowsReferencing(uid).filterValues { it > 0 })
        assertNull(Playlist.findById(playlist.id!!), "a public playlist goes with its owner")
        // Household data and other users are untouched.
        assertNotNull(AppUser.findById(keeper.id!!))
        assertNotNull(net.stewart.mediamanager.entity.Title.findById(title.id!!))
    }

    @Test
    fun `admin DeleteUser removes a user who has data, through the same cleanup`() {
        // Before V104 this failed: wish_list_item and playback_progress had
        // no delete rule, so the user row could not be deleted at all.
        val admin = createAdminUser(username = "da-admin-deleter")
        val user = createViewerUser(username = "da-admin-target")
        val transcode = createTranscode(createTitle(name = "Watched").id!!)
        WishListItem(user_id = user.id!!, tmdb_id = 2, tmdb_title = "W", tmdb_media_type = "MOVIE").save()
        PlaybackProgress(user_id = user.id!!, transcode_id = transcode.id!!, position_seconds = 1.0,
            updated_at = LocalDateTime.now()).save()

        val channel = authenticatedChannel(admin)
        try {
            runBlocking {
                AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
                    .deleteUser(userIdRequest { userId = user.id!! })
            }
        } finally {
            channel.shutdownNow()
        }
        assertNull(AppUser.findById(user.id!!))
        assertEquals(emptyMap(), rowsReferencing(user.id!!).filterValues { it > 0 })
    }

    @Test
    fun `wrong password is UNAUTHENTICATED and deletes nothing`() {
        createAdminUser(username = "da-admin")
        val user = createViewerUser(username = "da-wrongpw")
        val ex = assertFailsWith<StatusException> { deleteAccount(user, password = "not-it") }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
        assertNotNull(AppUser.findById(user.id!!))
    }

    @Test
    fun `blank password is INVALID_ARGUMENT`() {
        val user = createViewerUser(username = "da-blank")
        val ex = assertFailsWith<StatusException> { deleteAccount(user, password = "") }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        assertNotNull(AppUser.findById(user.id!!))
    }

    @Test
    fun `the last admin is refused and told what to do`() {
        val admin = createAdminUser(username = "da-only-admin")
        createViewerUser(username = "da-viewer")
        val ex = assertFailsWith<StatusException> { deleteAccount(admin) }
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        assertEquals(net.stewart.mediamanager.service.AccountDeletionService.LAST_ADMIN_MESSAGE, ex.status.description)
        assertNotNull(AppUser.findById(admin.id!!))
    }

    @Test
    fun `the last account, necessarily an admin, is refused`() {
        val admin = createAdminUser(username = "da-sole-user")
        val ex = assertFailsWith<StatusException> { deleteAccount(admin) }
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.status.code)
        assertTrue(AuthService.hasUsers(), "the server must not fall back to first-user setup")
    }

    @Test
    fun `an admin may leave while another admin remains`() {
        createAdminUser(username = "da-admin-stays")
        val leaving = createAdminUser(username = "da-admin-leaves")
        deleteAccount(leaving)
        assertNull(AppUser.findById(leaving.id!!))
    }

    @Test
    fun `every credential the user held stops working at once`() {
        createAdminUser(username = "da-cred-admin")
        val user = createViewerUser(username = "da-creds")
        val pair = JwtService.createTokenPair(user, "phone")
        val cookie = AuthService.createSession(user, "browser")
        assertNotNull(AuthService.validateCookieToken(cookie)) // warm the ~60s cache
        val deviceToken = "da-raw-device-token"
        DeviceToken(user_id = user.id!!, token_hash = AuthService.hashToken(deviceToken),
            device_name = "Roku").save()
        // Built now: the channel mints its bearer token at construction.
        val channel = authenticatedChannel(user)

        deleteAccount(user)

        assertNull(JwtService.validateAccessToken(pair.accessToken), "access token")
        assertTrue(JwtService.refresh(pair.refreshToken) !is RefreshResult.Success, "refresh token")
        assertNull(AuthService.validateCookieToken(cookie), "browser session, cache included")
        assertNull(PairingService.validateDeviceToken(deviceToken), "device token")

        // And over the wire: a bearer token minted before the delete is rejected.
        try {
            val ex = assertFailsWith<StatusException> {
                runBlocking {
                    ProfileServiceGrpcKt.ProfileServiceCoroutineStub(channel).getProfile(Empty.getDefaultInstance())
                }
            }
            assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `reachable while a password change is pending`() {
        createAdminUser(username = "da-mcp-admin")
        val user = createViewerUser(username = "da-mcp")
        user.must_change_password = true
        user.save()
        deleteAccount(user)
        assertNull(AppUser.findById(user.id!!))
    }

    @Test
    fun `reachable while a terms agreement is pending`() {
        createAdminUser(username = "da-terms-admin")
        val user = createViewerUser(username = "da-terms")
        AppConfig(config_key = LegalRequirements.PRIVACY_POLICY_VERSION_KEY, config_val = "7").save()
        AppConfig(config_key = LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, config_val = "7").save()
        LegalRequirements.refresh()
        try {
            deleteAccount(user, platform = LegalRequirements.PLATFORM_WEB)
            assertNull(AppUser.findById(user.id!!))
        } finally {
            AppConfig.deleteAll()
            LegalRequirements.refresh()
        }
    }
}
