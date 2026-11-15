package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LegalRequirements] — the singleton legal-version cache that
 * gates whether each user has agreed to the current privacy policy / TOU.
 */
class LegalRequirementsTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:legaltest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            // The LegalRequirements singleton caches required versions in
            // @Volatile fields. Tests in this class set them to non-zero;
            // if we leave them set, subsequent test classes (running in
            // the same JVM fork under low-parallelism / CI configs) get
            // PERMISSION_DENIED at the AuthInterceptor's legal-agreement
            // gate. Reset by clearing AppConfig and re-reading.
            AppConfig.deleteAll()
            LegalRequirements.refresh()
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun reset() {
        AppUser.deleteAll()
        AppConfig.deleteAll()
        // Ensure each test starts with a known cache + version state.
        LegalRequirements.refresh()
    }

    private fun setConfig(key: String, value: String) {
        AppConfig(config_key = key, config_val = value).save()
    }

    // ---------------------- refresh ----------------------

    @Test
    fun `refresh defaults all versions to zero and URLs to null when nothing is configured`() {
        // Already refreshed in @Before with empty AppConfig.
        assertEquals(0, LegalRequirements.privacyPolicyVersion)
        assertEquals(0, LegalRequirements.iosTermsOfUseVersion)
        assertEquals(0, LegalRequirements.webTermsOfUseVersion)
        assertEquals(0, LegalRequirements.androidTvTermsOfUseVersion)
        assertNull(LegalRequirements.privacyPolicyUrl)
        assertNull(LegalRequirements.iosTermsOfUseUrl)
        assertNull(LegalRequirements.webTermsOfUseUrl)
        assertNull(LegalRequirements.androidTvTermsOfUseUrl)
    }

    @Test
    fun `refresh loads versions and URLs from app_config`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        setConfig(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY, "2")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "5")
        setConfig(LegalRequirements.ANDROID_TV_TERMS_OF_USE_VERSION_KEY, "1")
        setConfig(LegalRequirements.PRIVACY_POLICY_URL_KEY, "https://example.test/privacy")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_URL_KEY, "https://example.test/tou")
        // ios + atv URLs deliberately blank — should be parsed as null.
        setConfig(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY, "")
        setConfig(LegalRequirements.ANDROID_TV_TERMS_OF_USE_URL_KEY, "   ")

        LegalRequirements.refresh()

        assertEquals(3, LegalRequirements.privacyPolicyVersion)
        assertEquals(2, LegalRequirements.iosTermsOfUseVersion)
        assertEquals(5, LegalRequirements.webTermsOfUseVersion)
        assertEquals(1, LegalRequirements.androidTvTermsOfUseVersion)
        assertEquals("https://example.test/privacy", LegalRequirements.privacyPolicyUrl)
        assertEquals("https://example.test/tou", LegalRequirements.webTermsOfUseUrl)
        assertNull(LegalRequirements.iosTermsOfUseUrl, "blank URL parses as null")
        assertNull(LegalRequirements.androidTvTermsOfUseUrl, "whitespace-only URL parses as null")
    }

    @Test
    fun `refresh treats non-integer version values as zero`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "not-a-number")
        LegalRequirements.refresh()
        assertEquals(0, LegalRequirements.privacyPolicyVersion)
    }

    // ---------------------- touVersionForPlatform ----------------------

    @Test
    fun `touVersionForPlatform routes by platform string`() {
        setConfig(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY, "2")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "5")
        setConfig(LegalRequirements.ANDROID_TV_TERMS_OF_USE_VERSION_KEY, "1")
        LegalRequirements.refresh()

        assertEquals(2, LegalRequirements.touVersionForPlatform(LegalRequirements.PLATFORM_IOS))
        assertEquals(5, LegalRequirements.touVersionForPlatform(LegalRequirements.PLATFORM_WEB))
        assertEquals(1, LegalRequirements.touVersionForPlatform(LegalRequirements.PLATFORM_ANDROID_TV))
        // Unknown platform falls back to the max across all configured ones.
        assertEquals(5, LegalRequirements.touVersionForPlatform("unknown"))
        assertEquals(5, LegalRequirements.touVersionForPlatform(null))
    }

    // ---------------------- isCompliant ----------------------

    @Test
    fun `isCompliant returns true when no versions are configured`() {
        // privacyPolicyVersion=0 means "not configured" — everyone is compliant.
        val u = AppUser(username = "u", display_name = "U", password_hash = "x").apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false))
    }

    @Test
    fun `isCompliant always returns true for admins regardless of agreed versions`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "2")
        LegalRequirements.refresh()

        val admin = AppUser(username = "a", display_name = "A", password_hash = "x",
            access_level = AppUser.ACCESS_LEVEL_ADMIN).apply { save() }
        // Admin has agreed to nothing, but is still compliant.
        assertTrue(LegalRequirements.isCompliant(admin.id!!, isAdmin = true))
    }

    @Test
    fun `isCompliant returns false when the user has not agreed to the current PP`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = null).apply { save() }
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false),
            "null agreed PP version is non-compliant")

        // Set to an older version — still non-compliant.
        u.privacy_policy_version = 2
        u.save()
        LegalRequirements.evict(u.id!!) // force re-check
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false))
    }

    @Test
    fun `isCompliant returns true when the user is at or above current PP and TOU`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "2")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3, terms_of_use_version = 2).apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false,
            requiredTou = 2))
    }

    @Test
    fun `isCompliant rejects when only PP is current but required TOU is behind`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3, terms_of_use_version = 1).apply { save() }
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false,
            requiredTou = 2))
        // ...but if required TOU is 0 (not enforced), only PP is checked.
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false,
            requiredTou = 0))
    }

    @Test
    fun `isCompliant returns false when the user does not exist`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        LegalRequirements.refresh()
        // No user with id 99999 — cached as (null, null) which is non-compliant.
        assertFalse(LegalRequirements.isCompliant(99_999L, isAdmin = false))
    }

    // ---------------------- recordAgreement + cache ----------------------

    @Test
    fun `recordAgreement updates the cache without a DB read`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "2")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x").apply { save() }
        // First call seeds the cache from the DB (null, null) -> non-compliant.
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false, requiredTou = 2))

        // Record an agreement WITHOUT touching the DB row. The cache update
        // alone must flip compliance.
        LegalRequirements.recordAgreement(u.id!!, ppVersion = 3, touVersion = 2)
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false, requiredTou = 2))
    }

    @Test
    fun `evict drops the user from the cache and forces a DB re-read on next check`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3).apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false))

        // Manually rewrite the user row to reflect a stale agreed version.
        u.privacy_policy_version = 1
        u.save()
        // Without eviction, the cache still says compliant.
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false))
        // After eviction, the next check re-reads the DB and sees stale.
        LegalRequirements.evict(u.id!!)
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false))
    }

    @Test
    fun `refresh clears the per-user cache so a version bump is picked up`() {
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, "3")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3).apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false))

        // Admin bumps the required PP version. After refresh, the cached
        // "compliant" status must be invalidated.
        AppConfig.findAll().single { it.config_key == LegalRequirements.PRIVACY_POLICY_VERSION_KEY }
            .apply { config_val = "4"; save() }
        LegalRequirements.refresh()
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false),
            "cache should be cleared by refresh — user is now behind on PP")
    }

    // ---------------------- violations: complete or off ----------------------

    /** A complete setup: every key set, every version 1. */
    private fun completeSettings(): MutableMap<String, String?> = mutableMapOf(
        LegalRequirements.PRIVACY_POLICY_URL_KEY to "https://example.com/privacy",
        LegalRequirements.PRIVACY_POLICY_VERSION_KEY to "1",
        LegalRequirements.IOS_TERMS_OF_USE_URL_KEY to "https://example.com/ios",
        LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY to "1",
        LegalRequirements.WEB_TERMS_OF_USE_URL_KEY to "https://example.com/web",
        LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY to "1",
        LegalRequirements.ANDROID_TV_TERMS_OF_USE_URL_KEY to "about:blank",
        LegalRequirements.ANDROID_TV_TERMS_OF_USE_VERSION_KEY to "1",
    )

    private fun saveAll(values: Map<String, String?>) {
        for ((key, value) in values) if (value != null) setConfig(key, value)
    }

    @Test
    fun `violations is empty when nothing is configured`() {
        assertTrue(LegalRequirements.violations(emptyMap()).isEmpty())
    }

    @Test
    fun `violations is empty for a complete setup, with about-blank standing in for a missing document`() {
        assertTrue(LegalRequirements.violations(completeSettings()).isEmpty())
    }

    @Test
    fun `violations is empty while the privacy policy version is unset, whatever else is filled in`() {
        val values = completeSettings().apply {
            remove(LegalRequirements.PRIVACY_POLICY_VERSION_KEY)
            remove(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY)
        }
        assertTrue(LegalRequirements.violations(values).isEmpty())
    }

    @Test
    fun `violations names the platform left unset beside one that is set`() {
        // The configuration that locked iOS users out: privacy policy and
        // web terms at version 1, iOS terms empty.
        val values = completeSettings().apply {
            remove(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY)
            remove(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY)
        }
        val found = LegalRequirements.violations(values)
        assertEquals(
            setOf(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY, LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY),
            found.map { it.key }.toSet())
        assertTrue(found.all { "iOS" in it.message }, "each message names the field: $found")
    }

    @Test
    fun `violations treats a version of 0 and a blank URL like unset ones`() {
        val values = completeSettings().apply {
            put(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, "0")
            put(LegalRequirements.ANDROID_TV_TERMS_OF_USE_URL_KEY, "   ")
        }
        assertEquals(
            setOf(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, LegalRequirements.ANDROID_TV_TERMS_OF_USE_URL_KEY),
            LegalRequirements.violations(values).map { it.key }.toSet())
    }

    @Test
    fun `violations requires a privacy policy URL once its version is set`() {
        val values = completeSettings().apply { remove(LegalRequirements.PRIVACY_POLICY_URL_KEY) }
        assertEquals(listOf(LegalRequirements.PRIVACY_POLICY_URL_KEY),
            LegalRequirements.violations(values).map { it.key })
    }

    @Test
    fun `violations refuses a version that is not a whole number, even with requirements off`() {
        // "1a" reads as 0, which would silently switch the requirement off.
        val found = LegalRequirements.violations(
            mapOf(LegalRequirements.PRIVACY_POLICY_VERSION_KEY to "1a"))
        assertEquals(listOf(LegalRequirements.PRIVACY_POLICY_VERSION_KEY), found.map { it.key })

        val negative = completeSettings().apply { put(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY, "-1") }
        assertEquals(setOf(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY),
            LegalRequirements.violations(negative).map { it.key }.toSet())
    }

    // ---------------------- rejectionFor: what a save may do ----------------------

    @Test
    fun `rejectionFor refuses to turn requirements on with a platform unset`() {
        val rejected = LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.PRIVACY_POLICY_VERSION_KEY to "1"))
        assertTrue(rejected != null && rejected.isNotEmpty())
    }

    @Test
    fun `rejectionFor accepts a complete setup saved in one go`() {
        assertNull(LegalRequirements.rejectionFor(completeSettings()))
    }

    @Test
    fun `rejectionFor refuses to blank a field of a complete setup`() {
        saveAll(completeSettings())
        val rejected = LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY to ""))
        assertEquals(listOf(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY), rejected?.map { it.key })
    }

    @Test
    fun `rejectionFor accepts a version bump and a URL change on a complete setup`() {
        saveAll(completeSettings())
        assertNull(LegalRequirements.rejectionFor(mapOf(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY to "2")))
        assertNull(LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY to "https://example.com/ios-v2")))
    }

    @Test
    fun `rejectionFor lets an incomplete setup be repaired one field at a time`() {
        // An older database: iOS terms never set.
        saveAll(completeSettings().apply {
            remove(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY)
            remove(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY)
        })
        // Setting the URL still leaves the version missing, but it is one
        // problem fewer, so it is allowed.
        assertNull(LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY to "about:blank")))
        // A change that fixes nothing is refused.
        assertTrue(LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY to "2")) != null)
    }

    @Test
    fun `rejectionFor never blocks a save that changes no legal setting`() {
        val incomplete = completeSettings().apply {
            remove(LegalRequirements.IOS_TERMS_OF_USE_URL_KEY)
            remove(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY)
        }
        saveAll(incomplete)
        // The settings form posts every field back; unchanged legal values
        // must not stop an unrelated setting from saving.
        assertNull(LegalRequirements.rejectionFor(incomplete + mapOf("keepa_api_key" to "abc")))
        assertNull(LegalRequirements.rejectionFor(emptyMap()))
    }

    @Test
    fun `rejectionFor lets requirements be switched off entirely`() {
        saveAll(completeSettings())
        assertNull(LegalRequirements.rejectionFor(
            mapOf(LegalRequirements.PRIVACY_POLICY_VERSION_KEY to "")))
    }
}
