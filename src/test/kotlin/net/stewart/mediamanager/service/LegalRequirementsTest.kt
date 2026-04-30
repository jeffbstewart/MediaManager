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
        setConfig("privacy_policy_version", "3")
        setConfig("ios_terms_of_use_version", "2")
        setConfig("web_terms_of_use_version", "5")
        setConfig("android_tv_terms_of_use_version", "1")
        setConfig("privacy_policy_url", "https://example.test/privacy")
        setConfig("web_terms_of_use_url", "https://example.test/tou")
        // ios + atv URLs deliberately blank — should be parsed as null.
        setConfig("ios_terms_of_use_url", "")
        setConfig("android_tv_terms_of_use_url", "   ")

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
        setConfig("privacy_policy_version", "not-a-number")
        LegalRequirements.refresh()
        assertEquals(0, LegalRequirements.privacyPolicyVersion)
    }

    // ---------------------- touVersionForPlatform ----------------------

    @Test
    fun `touVersionForPlatform routes by platform string`() {
        setConfig("ios_terms_of_use_version", "2")
        setConfig("web_terms_of_use_version", "5")
        setConfig("android_tv_terms_of_use_version", "1")
        LegalRequirements.refresh()

        assertEquals(2, LegalRequirements.touVersionForPlatform("ios"))
        assertEquals(5, LegalRequirements.touVersionForPlatform("web"))
        assertEquals(1, LegalRequirements.touVersionForPlatform("android_tv"))
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
        setConfig("privacy_policy_version", "3")
        setConfig("web_terms_of_use_version", "2")
        LegalRequirements.refresh()

        val admin = AppUser(username = "a", display_name = "A", password_hash = "x",
            access_level = 2).apply { save() }
        // Admin has agreed to nothing, but is still compliant.
        assertTrue(LegalRequirements.isCompliant(admin.id!!, isAdmin = true))
    }

    @Test
    fun `isCompliant returns false when the user has not agreed to the current PP`() {
        setConfig("privacy_policy_version", "3")
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
        setConfig("privacy_policy_version", "3")
        setConfig("web_terms_of_use_version", "2")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3, terms_of_use_version = 2).apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false,
            requiredTou = 2))
    }

    @Test
    fun `isCompliant rejects when only PP is current but required TOU is behind`() {
        setConfig("privacy_policy_version", "3")
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
        setConfig("privacy_policy_version", "3")
        LegalRequirements.refresh()
        // No user with id 99999 — cached as (null, null) which is non-compliant.
        assertFalse(LegalRequirements.isCompliant(99_999L, isAdmin = false))
    }

    // ---------------------- recordAgreement + cache ----------------------

    @Test
    fun `recordAgreement updates the cache without a DB read`() {
        setConfig("privacy_policy_version", "3")
        setConfig("web_terms_of_use_version", "2")
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
        setConfig("privacy_policy_version", "3")
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
        setConfig("privacy_policy_version", "3")
        LegalRequirements.refresh()

        val u = AppUser(username = "u", display_name = "U", password_hash = "x",
            privacy_policy_version = 3).apply { save() }
        assertTrue(LegalRequirements.isCompliant(u.id!!, isAdmin = false))

        // Admin bumps the required PP version. After refresh, the cached
        // "compliant" status must be invalidated.
        AppConfig.findAll().single { it.config_key == "privacy_policy_version" }
            .apply { config_val = "4"; save() }
        LegalRequirements.refresh()
        assertFalse(LegalRequirements.isCompliant(u.id!!, isAdmin = false),
            "cache should be cleared by refresh — user is now behind on PP")
    }
}
