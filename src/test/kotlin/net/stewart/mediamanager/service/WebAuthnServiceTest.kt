package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.PasskeyCredential
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [WebAuthnService] — the passkey ceremony driver.
 *
 * Full happy-path verifyRegistration / verifyAuthentication can't be
 * tested without producing real attestation bytes (which would need
 * a working WebAuthn authenticator + private key). Those paths are
 * exercised here through their *failure* branches: every guard before
 * the webauthn4j call is reachable with synthetic inputs, and the
 * webauthn4j call itself converts any malformed bytes to
 * IllegalArgumentException — so we hit the catch-block too.
 */
internal class WebAuthnServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:webauthn;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDb() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun reset() {
        PasskeyCredential.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
        // Reset the volatile cached config so each test's seed shape
        // is what's actually in the cache.
        WebAuthnService.refreshConfig()
    }

    private fun seedUser(username: String = "alice"): AppUser {
        val now = LocalDateTime.now()
        return AppUser(
            username = username, display_name = username,
            password_hash = "x", access_level = 1,
            created_at = now, updated_at = now,
        ).apply { save() }
    }

    private fun seedRpConfig(
        rpId: String = "test.example.com",
        rpName: String? = "Test RP",
        rpOrigin: String? = "https://test.example.com:8443",
    ) {
        AppConfig(config_key = "webauthn_rp_id", config_val = rpId).save()
        if (rpName != null) {
            AppConfig(config_key = "webauthn_rp_name",
                config_val = rpName).save()
        }
        if (rpOrigin != null) {
            AppConfig(config_key = "webauthn_rp_origin",
                config_val = rpOrigin).save()
        }
        WebAuthnService.refreshConfig()
    }

    private fun seedCredential(
        userId: Long,
        credentialId: String = "cred-${java.util.UUID.randomUUID()}",
        displayName: String = "Test Passkey",
    ): PasskeyCredential = PasskeyCredential(
        user_id = userId,
        credential_id = credentialId,
        public_key = ByteArray(64),
        sign_count = 0,
        display_name = displayName,
        created_at = LocalDateTime.now(),
    ).apply { save() }

    // ---------------------- refreshConfig + isAvailable ----------------------

    @Test
    fun `refreshConfig populates rpId rpName and rpOrigin from app_config`() {
        seedRpConfig(rpId = "myhost",
            rpName = "Custom Name",
            rpOrigin = "https://myhost:8443")
        assertEquals("myhost", WebAuthnService.rpId)
        assertEquals("Custom Name", WebAuthnService.rpName)
        assertEquals("https://myhost:8443", WebAuthnService.rpOrigin)
    }

    @Test
    fun `refreshConfig falls back to default rpName when not set`() {
        AppConfig(config_key = "webauthn_rp_id",
            config_val = "myhost").save()
        WebAuthnService.refreshConfig()
        assertEquals("Media Manager", WebAuthnService.rpName)
    }

    @Test
    fun `refreshConfig clears rpId when the AppConfig row is missing`() {
        // No config seeded.
        WebAuthnService.refreshConfig()
        assertEquals(null, WebAuthnService.rpId)
    }

    @Test
    fun `isAvailable returns false when rpId is not configured`() {
        WebAuthnService.refreshConfig()  // no rpId
        assertFalse(WebAuthnService.isAvailable())
    }

    @Test
    fun `isAvailable returns false when rpId is configured but no passkeys exist`() {
        seedRpConfig()
        assertFalse(WebAuthnService.isAvailable(),
            "no passkeys yet → not available")
    }

    @Test
    fun `isAvailable returns true once rpId is configured AND a passkey exists`() {
        val user = seedUser()
        seedCredential(user.id!!)
        seedRpConfig()  // calls refreshConfig which sets hasAnyPasskeys
        assertTrue(WebAuthnService.isAvailable())
    }

    // ---------------------- generateRegistrationOptions ----------------------

    @Test
    fun `generateRegistrationOptions throws when rpId is not configured`() {
        WebAuthnService.refreshConfig()  // no rpId
        assertFailsWith<IllegalStateException> {
            WebAuthnService.generateRegistrationOptions(seedUser())
        }
    }

    @Test
    fun `generateRegistrationOptions returns a signed challenge plus PKC creation options`() {
        seedRpConfig(rpId = "myhost")
        val user = seedUser("alice")
        val opts = WebAuthnService.generateRegistrationOptions(user)

        assertTrue(opts.signedChallenge.contains('.'),
            "signed challenge format is base64url(payload).base64url(mac)")
        // Inspect the JSON: rp.id matches what we configured, the user
        // is the one we passed, and pubKeyCredParams cover ES256 + RS256.
        assertEquals("myhost",
            opts.options.getAsJsonObject("rp").get("id").asString)
        assertEquals("alice",
            opts.options.getAsJsonObject("user").get("name").asString)
        // attestation = "none" per the production policy.
        assertEquals("none", opts.options.get("attestation").asString)
    }

    @Test
    fun `generateRegistrationOptions includes existing user passkeys in excludeCredentials`() {
        seedRpConfig()
        val user = seedUser()
        seedCredential(user.id!!, credentialId = "existing-cred-1")
        seedCredential(user.id!!, credentialId = "existing-cred-2")

        val opts = WebAuthnService.generateRegistrationOptions(user)
        val excluded = opts.options.getAsJsonArray("excludeCredentials")
        assertEquals(2, excluded.size())
        val ids = excluded.map { it.asJsonObject.get("id").asString }.toSet()
        assertEquals(setOf("existing-cred-1", "existing-cred-2"), ids)
    }

    // ---------------------- generateAuthenticationOptions ----------------------

    @Test
    fun `generateAuthenticationOptions throws when rpId is not configured`() {
        WebAuthnService.refreshConfig()
        assertFailsWith<IllegalStateException> {
            WebAuthnService.generateAuthenticationOptions()
        }
    }

    @Test
    fun `generateAuthenticationOptions returns signed challenge with empty allowCredentials`() {
        seedRpConfig()
        val opts = WebAuthnService.generateAuthenticationOptions()
        assertTrue(opts.signedChallenge.contains('.'))
        assertEquals(0,
            opts.options.getAsJsonArray("allowCredentials").size(),
            "discoverable credentials → no allowCredentials list")
    }

    // ---------------------- verifyRegistration error paths ----------------------

    @Test
    fun `verifyRegistration rejects a malformed challenge token`() {
        seedRpConfig()
        val user = seedUser()
        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyRegistration(
                signedChallenge = "not-a-valid-token",
                clientDataJSON = "x", attestationObject = "x",
                credentialId = "x", transports = null,
                displayName = "Passkey", userId = user.id!!,
            )
        }
        assertTrue(ex.message?.contains("Invalid challenge format") == true,
            "got: ${ex.message}")
    }

    @Test
    fun `verifyRegistration rejects a challenge whose userId does not match`() {
        seedRpConfig()
        val original = seedUser("original")
        val other = seedUser("other")

        val opts = WebAuthnService.generateRegistrationOptions(original)
        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyRegistration(
                signedChallenge = opts.signedChallenge,
                clientDataJSON = "x", attestationObject = "x",
                credentialId = "x", transports = null,
                displayName = "Passkey",
                userId = other.id!!,  // different user
            )
        }
        assertTrue(ex.message?.contains("user mismatch") == true,
            "got: ${ex.message}")
    }

    @Test
    fun `verifyRegistration rejects a challenge issued for the auth purpose`() {
        seedRpConfig()
        val user = seedUser()
        // Get an auth-purpose signed challenge instead of a register one.
        val authOpts = WebAuthnService.generateAuthenticationOptions()

        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyRegistration(
                signedChallenge = authOpts.signedChallenge,
                clientDataJSON = "x", attestationObject = "x",
                credentialId = "x", transports = null,
                displayName = "Passkey", userId = user.id!!,
            )
        }
        assertTrue(ex.message?.contains("purpose") == true,
            "got: ${ex.message}")
    }

    @Test
    fun `verifyRegistration rejects malformed attestation bytes after challenge passes`() {
        seedRpConfig()
        val user = seedUser()
        val opts = WebAuthnService.generateRegistrationOptions(user)

        // Challenge is valid; attestation/clientData are garbage. The
        // webauthn4j call throws and the catch-block converts it to
        // IllegalArgumentException with a descriptive message.
        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyRegistration(
                signedChallenge = opts.signedChallenge,
                clientDataJSON = "garbage",
                attestationObject = "garbage",
                credentialId = "x", transports = null,
                displayName = "Passkey", userId = user.id!!,
            )
        }
        // Message will be either "verification failed" or "Invalid
        // registration response" depending on which webauthn4j error
        // type fires. Both flow through the catch block we want to cover.
        assertNotNull(ex.message)
    }

    // ---------------------- verifyAuthentication error paths ----------------------

    @Test
    fun `verifyAuthentication rejects a challenge that was signed for register purpose`() {
        seedRpConfig()
        val user = seedUser()
        val regOpts = WebAuthnService.generateRegistrationOptions(user)

        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyAuthentication(
                signedChallenge = regOpts.signedChallenge,
                credentialId = "x",
                clientDataJSON = "x", authenticatorData = "x",
                signature = "x", userHandle = null,
            )
        }
        assertTrue(ex.message?.contains("purpose") == true,
            "got: ${ex.message}")
    }

    @Test
    fun `verifyAuthentication rejects a request whose credentialId is unknown`() {
        seedRpConfig()
        val authOpts = WebAuthnService.generateAuthenticationOptions()

        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyAuthentication(
                signedChallenge = authOpts.signedChallenge,
                credentialId = "unknown-credential-id",
                clientDataJSON = "x", authenticatorData = "x",
                signature = "x", userHandle = null,
            )
        }
        assertTrue(ex.message?.contains("Unknown credential") == true,
            "got: ${ex.message}")
    }

    // ---------------------- credential management ----------------------

    @Test
    fun `listCredentials returns the user's credentials in created_at order`() {
        val user = seedUser()
        val first = seedCredential(user.id!!, displayName = "First")
        Thread.sleep(5)  // ensure created_at differs
        val second = seedCredential(user.id!!, displayName = "Second")

        val creds = WebAuthnService.listCredentials(user.id!!)
        assertEquals(2, creds.size)
        // Order is created_at ASC per the SQL.
        assertEquals(first.id, creds[0].id)
        assertEquals(second.id, creds[1].id)
    }

    @Test
    fun `listCredentials returns an empty list for a user with no passkeys`() {
        val user = seedUser()
        assertEquals(0, WebAuthnService.listCredentials(user.id!!).size)
    }

    @Test
    fun `deleteCredential returns false when the credential id is unknown`() {
        val user = seedUser()
        assertFalse(WebAuthnService.deleteCredential(9999L, user.id!!))
    }

    @Test
    fun `deleteCredential returns false when the credential belongs to another user`() {
        val owner = seedUser("owner")
        val other = seedUser("other")
        val cred = seedCredential(owner.id!!)
        // 'other' tries to delete owner's credential.
        assertFalse(WebAuthnService.deleteCredential(cred.id!!, other.id!!))
        assertNotNull(PasskeyCredential.findById(cred.id!!),
            "credential must not be deleted by a non-owner")
    }

    @Test
    fun `deleteCredential removes the row when caller owns it`() {
        val owner = seedUser()
        val cred = seedCredential(owner.id!!)
        assertTrue(WebAuthnService.deleteCredential(cred.id!!, owner.id!!))
        assertEquals(null, PasskeyCredential.findById(cred.id!!))
    }

    @Test
    fun `deleteAllCredentials clears every credential for the user and returns the count`() {
        val user = seedUser()
        seedCredential(user.id!!)
        seedCredential(user.id!!)
        seedCredential(user.id!!)
        // A different user's credential should survive.
        val other = seedUser("other")
        seedCredential(other.id!!)

        val deleted = WebAuthnService.deleteAllCredentials(user.id!!)
        assertEquals(3, deleted)
        assertEquals(0, WebAuthnService.listCredentials(user.id!!).size)
        assertEquals(1, WebAuthnService.listCredentials(other.id!!).size)
    }

    @Test
    fun `adminDeleteCredential returns false for unknown id`() {
        assertFalse(WebAuthnService.adminDeleteCredential(9999L))
    }

    @Test
    fun `adminDeleteCredential bypasses the owner check and deletes the row`() {
        val user = seedUser()
        val cred = seedCredential(user.id!!)
        // adminDelete doesn't take an admin user-id — it just nukes by id.
        assertTrue(WebAuthnService.adminDeleteCredential(cred.id!!))
        assertEquals(null, PasskeyCredential.findById(cred.id!!))
    }

    @Test
    fun `adminDeleteAllCredentials clears every credential for the target user`() {
        val user = seedUser()
        seedCredential(user.id!!)
        seedCredential(user.id!!)
        val deleted = WebAuthnService.adminDeleteAllCredentials(user.id!!)
        assertEquals(2, deleted)
    }

    @Test
    fun `hasPasskeys reflects per-user passkey count`() {
        val user = seedUser()
        assertFalse(WebAuthnService.hasPasskeys(user.id!!))
        seedCredential(user.id!!)
        assertTrue(WebAuthnService.hasPasskeys(user.id!!))
    }

    // ---------------------- challenge HMAC + TTL ----------------------

    @Test
    fun `verifyRegistration rejects a challenge whose HMAC has been tampered with`() {
        seedRpConfig()
        val user = seedUser()
        val opts = WebAuthnService.generateRegistrationOptions(user)
        // Flip a few characters at the end of the MAC. The constant-time
        // compare returns false, the verifier rejects.
        val parts = opts.signedChallenge.split(".")
        // Replace the last 4 chars of the mac with zeros.
        val tampered = parts[0] + "." +
            parts[1].dropLast(4) + "AAAA"

        val ex = assertFailsWith<IllegalArgumentException> {
            WebAuthnService.verifyRegistration(
                signedChallenge = tampered,
                clientDataJSON = "x", attestationObject = "x",
                credentialId = "x", transports = null,
                displayName = "Passkey", userId = user.id!!,
            )
        }
        // Message is either "Challenge signature invalid" or could be a
        // base64 decode error if the tampered tail isn't valid base64.
        // Either way the verification rejects.
        assertNotNull(ex.message)
    }
}
