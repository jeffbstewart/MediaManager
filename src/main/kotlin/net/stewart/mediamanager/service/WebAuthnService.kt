@file:Suppress("DEPRECATION") // AuthenticatorImpl is deprecated but is the only Authenticator impl in webauthn4j

package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.*
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.PasskeyCredential
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WebAuthn/passkey service for registration and authentication ceremonies.
 *
 * Challenges are stateless: a random challenge + metadata is HMAC-signed with the JWT
 * signing key and returned to the client as an opaque token. On verification, the server
 * validates the HMAC and checks the 5-minute TTL.
 */
object WebAuthnService {
    private val log = LoggerFactory.getLogger(WebAuthnService::class.java)
    private val gson = Gson()
    private val random = SecureRandom()
    private val objectConverter = ObjectConverter()
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()

    private const val CHALLENGE_TTL_SECONDS = 300 // 5 minutes
    private const val CHALLENGE_BYTES = 32

    // Cached RP config — refreshed from app_config on startup and settings save
    @Volatile var rpId: String? = null; private set
    @Volatile var rpName: String = "Media Manager"; private set
    @Volatile var rpOrigin: String? = null; private set

    // Cached flag: are there any passkeys in the database?
    @Volatile private var hasAnyPasskeys: Boolean = false

    /** Load RP config from app_config. Called on startup and when admin saves settings. */
    fun refreshConfig() {
        val configs = AppConfig.findAll().associateBy { it.config_key }
        rpId = configs["webauthn_rp_id"]?.config_val?.takeIf { it.isNotBlank() }
        rpName = configs["webauthn_rp_name"]?.config_val?.takeIf { it.isNotBlank() } ?: "Media Manager"
        rpOrigin = configs["webauthn_rp_origin"]?.config_val?.takeIf { it.isNotBlank() }
        hasAnyPasskeys = countAllCredentials() > 0
        log.info("WebAuthn config refreshed: rpId={} rpOrigin={} rpName={} hasPasskeys={}",
            rpId, rpOrigin, rpName, hasAnyPasskeys)
    }

    /** Whether passkey login should be offered (RP configured + at least one passkey exists). */
    fun isAvailable(): Boolean = rpId != null && hasAnyPasskeys

    // --- Registration ---

    data class RegistrationOptions(
        val signedChallenge: String,
        val options: JsonObject
    )

    /**
     * Generates WebAuthn registration options for a user.
     * Returns a signed challenge token and the PublicKeyCredentialCreationOptions JSON.
     */
    fun generateRegistrationOptions(user: AppUser): RegistrationOptions {
        val rpId = requireRpId()
        val challenge = randomChallenge()
        val signedChallenge = signChallenge(challenge, userId = user.id, purpose = "register")

        // Build excludeCredentials from user's existing passkeys
        val existing = findCredentialsByUserId(user.id!!)
        val excludeCredentials = existing.map { cred ->
            JsonObject().apply {
                addProperty("type", "public-key")
                addProperty("id", cred.credential_id)
                cred.transports?.let { t ->
                    add("transports", gson.toJsonTree(t.split(",")))
                }
            }
        }

        val options = JsonObject().apply {
            add("rp", JsonObject().apply {
                addProperty("name", rpName)
                addProperty("id", rpId)
            })
            add("user", JsonObject().apply {
                addProperty("id", base64UrlEncode(user.id.toString().toByteArray()))
                addProperty("name", user.username)
                addProperty("displayName", user.display_name)
            })
            addProperty("challenge", base64UrlEncode(challenge))
            add("pubKeyCredParams", gson.toJsonTree(listOf(
                mapOf("alg" to -7, "type" to "public-key"),   // ES256
                mapOf("alg" to -257, "type" to "public-key")  // RS256
            )))
            addProperty("timeout", 300000)
            addProperty("attestation", "none")
            add("authenticatorSelection", JsonObject().apply {
                addProperty("residentKey", "preferred")
                addProperty("requireResidentKey", false)
                addProperty("userVerification", "preferred")
            })
            add("excludeCredentials", gson.toJsonTree(excludeCredentials))
        }

        return RegistrationOptions(signedChallenge, options)
    }

    /**
     * Verifies a registration response and stores the new credential.
     *
     * @param signedChallenge The opaque challenge token from generateRegistrationOptions
     * @param clientDataJSON Base64URL-encoded clientDataJSON from the authenticator
     * @param attestationObject Base64URL-encoded attestationObject from the authenticator
     * @param credentialId Base64URL-encoded credential ID
     * @param transports Comma-separated transport hints (e.g. "internal,hybrid")
     * @param displayName User-chosen name for this passkey
     * @return The persisted PasskeyCredential
     */
    fun verifyRegistration(
        signedChallenge: String,
        clientDataJSON: String,
        attestationObject: String,
        credentialId: String,
        transports: String?,
        displayName: String,
        userId: Long
    ): PasskeyCredential {
        val rpId = requireRpId()
        val challengeData = verifyAndDecodeChallenge(signedChallenge, purpose = "register")

        // Verify the challenge was issued for this user
        val challengeUserId = challengeData.getAsJsonPrimitive("userId")?.asLong
            ?: throw IllegalArgumentException("Challenge missing userId")
        if (challengeUserId != userId) {
            throw IllegalArgumentException("Challenge user mismatch")
        }

        val challengeBytes = base64UrlDecode(challengeData.getAsJsonPrimitive("challenge").asString)

        val registrationRequest = RegistrationRequest(
            base64UrlDecode(attestationObject),
            base64UrlDecode(clientDataJSON)
        )

        val origin = determineOrigin()
        val serverProperty = ServerProperty(origin, rpId, DefaultChallenge(challengeBytes), null)

        val registrationParameters = RegistrationParameters(serverProperty, null, false, true)

        val registrationData = try {
            webAuthnManager.verify(registrationRequest, registrationParameters)
        } catch (e: VerificationException) {
            log.warn("WebAuthn registration verification failed: {}", e.message)
            throw IllegalArgumentException("Registration verification failed: ${e.message}")
        } catch (e: Exception) {
            log.warn("WebAuthn registration parse failed: {}", e.message)
            throw IllegalArgumentException("Invalid registration response: ${e.message}")
        }

        val attestedData = registrationData.attestationObject!!.authenticatorData.attestedCredentialData
            ?: throw IllegalArgumentException("No attested credential data")

        val publicKeyBytes = attestedCredentialDataConverter.convert(attestedData)
        val signCount = registrationData.attestationObject!!.authenticatorData.signCount

        val credential = PasskeyCredential(
            user_id = userId,
            credential_id = credentialId,
            public_key = publicKeyBytes,
            sign_count = signCount,
            transports = transports?.takeIf { it.isNotBlank() },
            display_name = displayName.take(255).ifBlank { "Passkey" },
            created_at = LocalDateTime.now()
        )
        credential.save()
        hasAnyPasskeys = true

        log.info("AUDIT: Passkey registered for user_id={} credentialId={}...{}", userId,
            credentialId.take(8), credentialId.takeLast(4))
        return credential
    }

    // --- Authentication ---

    data class AuthenticationOptions(
        val signedChallenge: String,
        val options: JsonObject
    )

    /**
     * Generates WebAuthn authentication options.
     * Uses discoverable credentials (no allowCredentials) to avoid username enumeration.
     */
    fun generateAuthenticationOptions(): AuthenticationOptions {
        val rpId = requireRpId()
        val challenge = randomChallenge()
        val signedChallenge = signChallenge(challenge, userId = null, purpose = "authenticate")

        val options = JsonObject().apply {
            addProperty("challenge", base64UrlEncode(challenge))
            addProperty("timeout", 300000)
            addProperty("rpId", rpId)
            addProperty("userVerification", "preferred")
            add("allowCredentials", gson.toJsonTree(emptyList<Any>()))
        }

        return AuthenticationOptions(signedChallenge, options)
    }

    /**
     * Verifies an authentication response and returns the authenticated user.
     *
     * @return The AppUser who owns the credential
     * @throws IllegalArgumentException if verification fails
     */
    fun verifyAuthentication(
        signedChallenge: String,
        credentialId: String,
        clientDataJSON: String,
        authenticatorData: String,
        signature: String,
        userHandle: String?
    ): AppUser {
        val rpId = requireRpId()
        val challengeData = verifyAndDecodeChallenge(signedChallenge, purpose = "authenticate")
        val challengeBytes = base64UrlDecode(challengeData.getAsJsonPrimitive("challenge").asString)

        // Look up the credential via indexed query
        val credential = findCredentialByCredentialId(credentialId)
            ?: throw IllegalArgumentException("Unknown credential")

        // Reconstruct the authenticator for verification
        val attestedCredentialData = attestedCredentialDataConverter.convert(credential.public_key)
        @Suppress("DEPRECATION") // AuthenticatorImpl is deprecated but is the only Authenticator implementation
        val authenticator = AuthenticatorImpl(attestedCredentialData, null, credential.sign_count)

        val authenticationRequest = AuthenticationRequest(
            base64UrlDecode(credentialId),
            base64UrlDecode(userHandle ?: ""),
            base64UrlDecode(authenticatorData),
            base64UrlDecode(clientDataJSON),
            null,
            base64UrlDecode(signature)
        )

        val origin = determineOrigin()
        val serverProperty = ServerProperty(origin, rpId, DefaultChallenge(challengeBytes), null)
        val authenticationParameters = AuthenticationParameters(
            serverProperty,
            authenticator,
            null,
            true
        )

        val authenticationData = try {
            webAuthnManager.verify(authenticationRequest, authenticationParameters)
        } catch (e: VerificationException) {
            log.warn("WebAuthn authentication verification failed for credential {}...{}: {}",
                credentialId.take(8), credentialId.takeLast(4), e.message)
            throw IllegalArgumentException("Authentication verification failed: ${e.message}")
        } catch (e: Exception) {
            log.warn("WebAuthn authentication parse failed for credential {}...{}: {}",
                credentialId.take(8), credentialId.takeLast(4), e.message)
            throw IllegalArgumentException("Invalid authentication response: ${e.message}")
        }

        // Update sign count and last used
        val newSignCount = authenticationData.authenticatorData!!.signCount
        credential.sign_count = newSignCount
        credential.last_used_at = LocalDateTime.now()
        credential.save()

        val user = AppUser.findById(credential.user_id)
            ?: throw IllegalArgumentException("User not found for credential")

        log.info("AUDIT: Passkey authentication succeeded for user '{}' (credential {}...{})",
            user.username, credentialId.take(8), credentialId.takeLast(4))
        return user
    }

    // --- Credential Management ---

    fun listCredentials(userId: Long): List<PasskeyCredential> =
        findCredentialsByUserId(userId)

    fun deleteCredential(credentialId: Long, userId: Long): Boolean {
        val cred = PasskeyCredential.findById(credentialId)
        if (cred == null || cred.user_id != userId) return false
        cred.delete()
        refreshHasAnyPasskeys()
        log.info("AUDIT: Passkey deleted id={} for user_id={}", credentialId, userId)
        return true
    }

    fun deleteAllCredentials(userId: Long): Int {
        val creds = findCredentialsByUserId(userId)
        creds.forEach { it.delete() }
        refreshHasAnyPasskeys()
        if (creds.isNotEmpty()) {
            log.info("AUDIT: All {} passkeys deleted for user_id={}", creds.size, userId)
        }
        return creds.size
    }

    /** Admin: delete a specific credential for any user. */
    fun adminDeleteCredential(credentialId: Long): Boolean {
        val cred = PasskeyCredential.findById(credentialId) ?: return false
        val userId = cred.user_id
        cred.delete()
        refreshHasAnyPasskeys()
        log.info("AUDIT: Admin deleted passkey id={} for user_id={}", credentialId, userId)
        return true
    }

    /** Admin: delete all credentials for any user. */
    fun adminDeleteAllCredentials(userId: Long): Int = deleteAllCredentials(userId)

    fun hasPasskeys(userId: Long): Boolean =
        countCredentialsByUserId(userId) > 0

    private fun refreshHasAnyPasskeys() {
        hasAnyPasskeys = countAllCredentials() > 0
    }

    // --- DB queries (use indexes instead of in-memory scans) ---

    private fun findCredentialByCredentialId(credentialId: String): PasskeyCredential? =
        JdbiOrm.jdbi().withHandle<PasskeyCredential?, Exception> { handle ->
            val id = handle.createQuery(
                "SELECT id FROM passkey_credential WHERE credential_id = :cid"
            ).bind("cid", credentialId).mapTo(Long::class.java).firstOrNull()
            id?.let { PasskeyCredential.findById(it) }
        }

    private fun findCredentialsByUserId(userId: Long): List<PasskeyCredential> =
        JdbiOrm.jdbi().withHandle<List<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT id FROM passkey_credential WHERE user_id = :uid ORDER BY created_at"
            ).bind("uid", userId).mapTo(Long::class.java).list()
        }.mapNotNull { PasskeyCredential.findById(it) }

    private fun countCredentialsByUserId(userId: Long): Int =
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM passkey_credential WHERE user_id = :uid")
                .bind("uid", userId).mapTo(Int::class.java).one()
        }

    private fun countAllCredentials(): Int =
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM passkey_credential")
                .mapTo(Int::class.java).one()
        }

    // --- Challenge HMAC ---

    private fun signChallenge(challenge: ByteArray, userId: Long?, purpose: String): String {
        val payload = JsonObject().apply {
            addProperty("challenge", base64UrlEncode(challenge))
            addProperty("timestamp", Instant.now().epochSecond)
            addProperty("purpose", purpose)
            userId?.let { addProperty("userId", it) }
        }
        val payloadBytes = payload.toString().toByteArray()
        val mac = hmacSha256(payloadBytes)
        // Format: base64url(payload) + "." + base64url(mac)
        return base64UrlEncode(payloadBytes) + "." + base64UrlEncode(mac)
    }

    private fun verifyAndDecodeChallenge(signedChallenge: String, purpose: String): JsonObject {
        val parts = signedChallenge.split(".", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid challenge format")

        val payloadBytes = base64UrlDecode(parts[0])
        val expectedMac = base64UrlDecode(parts[1])
        val actualMac = hmacSha256(payloadBytes)

        if (!constantTimeEquals(expectedMac, actualMac)) {
            throw IllegalArgumentException("Challenge signature invalid")
        }

        val payload = gson.fromJson(String(payloadBytes), JsonObject::class.java)

        // Check TTL
        val timestamp = payload.getAsJsonPrimitive("timestamp").asLong
        val elapsed = Instant.now().epochSecond - timestamp
        if (elapsed < 0 || elapsed > CHALLENGE_TTL_SECONDS) {
            throw IllegalArgumentException("Challenge expired")
        }

        // Check purpose
        val actualPurpose = payload.getAsJsonPrimitive("purpose").asString
        if (actualPurpose != purpose) {
            throw IllegalArgumentException("Challenge purpose mismatch")
        }

        return payload
    }

    private fun hmacSha256(data: ByteArray): ByteArray {
        val key = JwtService.getSigningKeyBytes()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    // --- Origin ---

    private fun determineOrigin(): Origin {
        val rpId = requireRpId()
        // Use explicit origin if configured (needed for non-standard ports like :8443)
        rpOrigin?.let { return Origin.create(it) }
        // Fall back to default: localhost gets http for dev, everything else gets https
        return if (rpId == "localhost") {
            Origin.create("http://localhost:4200")
        } else {
            Origin.create("https://$rpId")
        }
    }

    // --- Helpers ---

    private fun requireRpId(): String =
        rpId ?: throw IllegalStateException("WebAuthn RP ID not configured (set webauthn_rp_id in admin settings)")

    private fun randomChallenge(): ByteArray {
        val bytes = ByteArray(CHALLENGE_BYTES)
        random.nextBytes(bytes)
        return bytes
    }

    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()

    private fun base64UrlEncode(bytes: ByteArray): String = base64UrlEncoder.encodeToString(bytes)
    private fun base64UrlDecode(str: String): ByteArray = base64UrlDecoder.decode(str)
}
