package net.stewart.mediamanager.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.RefreshToken
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int // seconds
)

sealed class RefreshResult {
    data class Success(val tokenPair: TokenPair) : RefreshResult()
    data object InvalidToken : RefreshResult()
    data object FamilyRevoked : RefreshResult()
}

/**
 * JWT authentication service for the iOS API.
 *
 * Uses HMAC-SHA256 with an auto-generated signing key stored in app_config.
 * Supports dual-key validation during key rotation (current + previous key).
 *
 * Key rotation procedure:
 * 1. Copy current jwt_signing_key value to jwt_signing_key_previous in app_config
 * 2. Delete the jwt_signing_key row (a new key is auto-generated on next use)
 * 3. Restart the server
 * 4. For 15 minutes, JWTs signed with either key are accepted (dual-key validation)
 * 5. After access tokens expire (15min), remove jwt_signing_key_previous from app_config
 * 6. All users must re-authenticate via refresh tokens (which are DB-based, not affected)
 */
object JwtService {
    private val log = LoggerFactory.getLogger(JwtService::class.java)

    private const val ISSUER = "mediamanager"
    private const val AUDIENCE = "mediamanager-api"
    private const val ACCESS_TOKEN_SECONDS = 900 // 15 minutes
    private const val REFRESH_TOKEN_DAYS = 30L
    private const val GRACE_PERIOD_SECONDS = 60L
    private const val MAX_REFRESH_TOKENS_PER_USER = 10
    private const val CONFIG_KEY_SIGNING = "jwt_signing_key"
    private const val CONFIG_KEY_SIGNING_PREVIOUS = "jwt_signing_key_previous"

    /**
     * Creates a new access + refresh token pair for the given user.
     */
    fun createTokenPair(user: AppUser, deviceName: String): TokenPair {
        val accessToken = createAccessToken(user)
        val refreshToken = createRefreshToken(user, deviceName)
        return TokenPair(accessToken, refreshToken, ACCESS_TOKEN_SECONDS)
    }

    /**
     * Validates a JWT access token and returns the authenticated user.
     * Returns null if the token is invalid, expired, or the user no longer exists.
     */
    fun validateAccessToken(token: String): AppUser? {
        val decoded = verifyToken(token) ?: return null
        val tokenType = decoded.getClaim("type").asString()
        if (tokenType != "access") return null
        val userId = decoded.subject?.toLongOrNull() ?: return null
        return AppUser.findById(userId)
    }

    /**
     * Refreshes a token pair using a refresh token.
     * Implements rotation with 60-second grace period for concurrent request handling.
     * Detects potential token theft via family-based revocation when an already-replaced
     * token is reused outside the grace window.
     */
    fun refresh(rawRefreshToken: String): RefreshResult {
        val tokenHash = hashToken(rawRefreshToken)
        val now = LocalDateTime.now()

        data class TokenLookup(
            val id: Long, val userId: Long, val familyId: String, val deviceName: String,
            val expiresAt: LocalDateTime, val revoked: Boolean,
            val replacedByHash: String?, val replacedAt: LocalDateTime?
        )

        val rt = JdbiOrm.jdbi().withHandle<TokenLookup?, Exception> { handle ->
            handle.createQuery(
                """SELECT id, user_id, family_id, device_name, expires_at, revoked,
                          replaced_by_hash, replaced_at
                   FROM refresh_token WHERE token_hash = :hash"""
            )
                .bind("hash", tokenHash)
                .map { rs, _ ->
                    TokenLookup(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("family_id"),
                        rs.getString("device_name"), rs.getTimestamp("expires_at").toLocalDateTime(),
                        rs.getBoolean("revoked"), rs.getString("replaced_by_hash"),
                        rs.getTimestamp("replaced_at")?.toLocalDateTime()
                    )
                }
                .firstOrNull()
        } ?: return RefreshResult.InvalidToken

        if (rt.revoked) {
            log.warn("AUDIT: Revoked refresh token used for user_id={}", rt.userId)
            return RefreshResult.InvalidToken
        }

        if (rt.expiresAt.isBefore(now)) {
            return RefreshResult.InvalidToken
        }

        // Already replaced — check grace period
        if (rt.replacedAt != null) {
            val secondsSinceReplacement = Duration.between(rt.replacedAt, now).seconds
            if (secondsSinceReplacement <= GRACE_PERIOD_SECONDS) {
                // Grace period: concurrent request — generate new tokens in same family
                val user = AppUser.findById(rt.userId) ?: return RefreshResult.InvalidToken
                val newRefreshToken = createRefreshToken(user, rt.deviceName, rt.familyId)
                val newAccessToken = createAccessToken(user)
                log.info("AUDIT: Refresh token grace period reuse for user_id={} family={}", rt.userId, rt.familyId)
                return RefreshResult.Success(TokenPair(newAccessToken, newRefreshToken, ACCESS_TOKEN_SECONDS))
            } else {
                // Token theft detected — revoke entire family
                revokeFamily(rt.familyId)
                log.warn(
                    "AUDIT: Refresh token reuse after grace period — family {} revoked for user_id={}",
                    rt.familyId, rt.userId
                )
                return RefreshResult.FamilyRevoked
            }
        }

        // Normal rotation
        val user = AppUser.findById(rt.userId) ?: return RefreshResult.InvalidToken
        val newRefreshToken = createRefreshToken(user, rt.deviceName, rt.familyId)
        val newRefreshHash = hashToken(newRefreshToken)

        // Mark old token as replaced
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """UPDATE refresh_token SET replaced_by_hash = :newHash, replaced_at = :now
                   WHERE id = :id"""
            )
                .bind("newHash", newRefreshHash)
                .bind("now", now)
                .bind("id", rt.id)
                .execute()
        }

        val newAccessToken = createAccessToken(user)
        log.info("AUDIT: Refresh token rotated for user_id={} family={}", rt.userId, rt.familyId)
        return RefreshResult.Success(TokenPair(newAccessToken, newRefreshToken, ACCESS_TOKEN_SECONDS))
    }

    /**
     * Revokes a single refresh token.
     */
    fun revoke(rawRefreshToken: String): Boolean {
        val tokenHash = hashToken(rawRefreshToken)
        val updated = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE refresh_token SET revoked = TRUE WHERE token_hash = :hash AND revoked = FALSE")
                .bind("hash", tokenHash)
                .execute()
        }
        if (updated > 0) {
            log.info("AUDIT: Refresh token revoked")
        }
        return updated > 0
    }

    /**
     * Revokes all refresh tokens for a user. Called during password change/reset.
     */
    fun revokeAllForUser(userId: Long) {
        val revoked = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE refresh_token SET revoked = TRUE WHERE user_id = :uid AND revoked = FALSE")
                .bind("uid", userId)
                .execute()
        }
        if (revoked > 0) {
            log.info("AUDIT: Revoked {} refresh tokens for user_id={}", revoked, userId)
        }
    }

    /**
     * Cleans up expired refresh tokens from the database.
     */
    fun cleanupExpiredTokens() {
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM refresh_token WHERE expires_at < :now")
                .bind("now", LocalDateTime.now())
                .execute()
        }
        if (deleted > 0) {
            log.info("Cleaned up {} expired refresh tokens", deleted)
        }
    }

    // --- Internal ---

    private fun createAccessToken(user: AppUser): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(user.id.toString())
            .withClaim("type", "access")
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(ACCESS_TOKEN_SECONDS.toLong()))
            .sign(currentAlgorithm())
    }

    private fun createRefreshToken(user: AppUser, deviceName: String, familyId: String? = null): String {
        val token = UUID.randomUUID().toString()
        val tokenHash = hashToken(token)
        val now = LocalDateTime.now()

        enforceTokenCap(user.id!!)

        RefreshToken(
            user_id = user.id!!,
            token_hash = tokenHash,
            family_id = familyId ?: UUID.randomUUID().toString(),
            device_name = deviceName,
            created_at = now,
            expires_at = now.plusDays(REFRESH_TOKEN_DAYS),
            revoked = false
        ).save()

        return token
    }

    private fun enforceTokenCap(userId: Long) {
        val activeTokenIds = JdbiOrm.jdbi().withHandle<List<Long>, Exception> { handle ->
            handle.createQuery(
                """SELECT id FROM refresh_token
                   WHERE user_id = :uid AND revoked = FALSE AND expires_at > :now
                   ORDER BY created_at DESC"""
            )
                .bind("uid", userId)
                .bind("now", LocalDateTime.now())
                .mapTo(Long::class.java)
                .list()
        }
        if (activeTokenIds.size >= MAX_REFRESH_TOKENS_PER_USER) {
            val toRevoke = activeTokenIds.drop(MAX_REFRESH_TOKENS_PER_USER - 1)
            JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    "UPDATE refresh_token SET revoked = TRUE WHERE id IN (<ids>)"
                )
                    .bindList("ids", toRevoke)
                    .execute()
            }
            log.info("AUDIT: Revoked {} oldest refresh tokens for user_id={} (cap enforcement)", toRevoke.size, userId)
        }
    }

    private fun revokeFamily(familyId: String) {
        val revoked = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE refresh_token SET revoked = TRUE WHERE family_id = :fid AND revoked = FALSE")
                .bind("fid", familyId)
                .execute()
        }
        log.warn("AUDIT: Revoked {} refresh tokens in family {}", revoked, familyId)
    }

    private fun currentAlgorithm(): Algorithm {
        val key = getOrCreateSigningKey()
        return Algorithm.HMAC256(hexToBytes(key))
    }

    private fun previousAlgorithm(): Algorithm? {
        val prevKey = getConfigValue(CONFIG_KEY_SIGNING_PREVIOUS) ?: return null
        return Algorithm.HMAC256(hexToBytes(prevKey))
    }

    private fun verifyToken(token: String): DecodedJWT? {
        try {
            return JWT.require(currentAlgorithm())
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .build()
                .verify(token)
        } catch (_: JWTVerificationException) {
            // Fall through to try previous key
        }

        val prevAlg = previousAlgorithm() ?: return null
        return try {
            JWT.require(prevAlg)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .build()
                .verify(token)
        } catch (_: JWTVerificationException) {
            null
        }
    }

    private fun getOrCreateSigningKey(): String {
        val existing = getConfigValue(CONFIG_KEY_SIGNING)
        if (existing != null) return existing

        val key = generateRandomKey()
        AppConfig(
            config_key = CONFIG_KEY_SIGNING,
            config_val = key,
            description = "JWT HMAC-SHA256 signing key (auto-generated)"
        ).save()
        log.info("Generated new JWT signing key")
        return key
    }

    private fun generateRandomKey(): String {
        val bytes = ByteArray(32) // 256 bits
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getConfigValue(key: String): String? =
        JdbiOrm.jdbi().withHandle<String?, Exception> { handle ->
            handle.createQuery("SELECT config_val FROM app_config WHERE config_key = :key")
                .bind("key", key)
                .mapTo(String::class.java)
                .firstOrNull()
        }
}
