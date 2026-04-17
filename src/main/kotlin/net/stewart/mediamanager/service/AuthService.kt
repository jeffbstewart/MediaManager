package net.stewart.mediamanager.service

import com.github.vokorm.count
import com.gitlab.mvysny.jdbiorm.JdbiOrm
// Vaadin session imports removed — all auth is now via Armeria request context
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LoginAttempt
import net.stewart.mediamanager.entity.SessionToken
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class LoginResult {
    data class Success(val user: AppUser) : LoginResult()
    data object Failed : LoginResult()
    data class RateLimited(val retryAfterSeconds: Long) : LoginResult()
}

internal fun maskUsername(raw: String): String {
    if (raw.length <= 3) return "***"
    val truncated = if (raw.length > 30) raw.substring(0, 27) + "..." else raw
    return truncated.substring(0, 2) +
        "*".repeat(truncated.length - 4) +
        truncated.substring(truncated.length - 2)
}

object AuthService {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    private fun countLogin(result: String) {
        MetricsRegistry.registry.counter("mm_login_attempts_total", "result", result).increment()
    }

    const val COOKIE_NAME = "mm_auth"
    const val SESSION_DAYS = 30L
    private const val RATE_LIMIT_WINDOW_MINUTES = 15L
    private const val RATE_LIMIT_THRESHOLD = 5
    private const val RATE_LIMIT_BASE_COOLDOWN_SECONDS = 30L
    private const val RATE_LIMIT_MAX_COOLDOWN_SECONDS = 900L // 15 minutes
    private const val LOCKOUT_THRESHOLD = 20
    private const val DAILY_FAILURE_CAP = 100

    // --- In-memory auth token cache ---
    // Eliminates 4 DB round-trips per servlet request (image, video, progress, etc.)
    // by caching validated token→user for 60 seconds. Without this, 30 concurrent
    // image requests from a single page load cause 150 DB connection checkouts against
    // a 10-connection pool, starving the health check and other requests.
    private const val TOKEN_CACHE_TTL_SECONDS = 60L
    private const val LAST_USED_UPDATE_INTERVAL_SECONDS = 300L // 5 minutes

    private data class CachedAuth(
        val user: AppUser,
        val tokenId: Long,
        val cachedAt: Instant,
        val lastUsedUpdatedAt: Instant,
    )

    private val tokenCache = ConcurrentHashMap<String, CachedAuth>()

    /** Evict a specific token hash from the cache (on logout, revoke, password change). */
    fun evictTokenCache(tokenHash: String) {
        tokenCache.remove(tokenHash)
    }

    /** Evict all cached entries for a user (on password change, lock, session invalidation). */
    fun evictTokenCacheForUser(userId: Long) {
        tokenCache.entries.removeIf { it.value.user.id == userId }
    }

    /** Clear entire token cache (for testing or emergency). */
    fun clearTokenCache() {
        tokenCache.clear()
    }

    // --- Cached hasUsers() ---
    // AppUser.count() was called on every servlet request via AuthFilter. Cache the result
    // since it only changes when users are created or deleted.
    @Volatile
    private var hasUsersCache: Boolean? = null

    fun hasUsers(): Boolean {
        hasUsersCache?.let { return it }
        val result = AppUser.count() > 0
        hasUsersCache = result
        return result
    }

    /** Call when a user is created or deleted to refresh the hasUsers cache. */
    fun invalidateHasUsersCache() {
        hasUsersCache = null
    }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun login(username: String, password: String, ip: String): LoginResult {
        // Check rate limit (both per-IP and per-username)
        val rateLimitResult = checkRateLimit(ip, username)
        if (rateLimitResult != null) {
            countLogin("rate_limited")
            return rateLimitResult
        }

        val userId = JdbiOrm.jdbi().withHandle<Long?, Exception> { handle ->
            handle.createQuery("SELECT id FROM app_user WHERE LOWER(username) = LOWER(:u) LIMIT 1")
                .bind("u", username)
                .mapTo(Long::class.java)
                .firstOrNull()
        }
        val user = userId?.let { AppUser.findById(it) }

        // Reject locked accounts (still run BCrypt to equalize timing)
        if (user?.locked == true) {
            PasswordService.dummyVerify()
            log.info("AUDIT: Login rejected — account '{}' is locked", maskUsername(username))
            countLogin("locked")
            return LoginResult.Failed
        }

        // M1 fix: equalize timing whether user exists or not (prevents account enumeration)
        val matched = if (user != null) {
            PasswordService.verify(password, user.password_hash)
        } else {
            PasswordService.dummyVerify()
            false
        }

        // Record the attempt
        LoginAttempt(
            username = username,
            ip_address = ip,
            attempted_at = LocalDateTime.now(),
            success = matched
        ).save()

        return if (user != null && matched) {
            log.info("AUDIT: Login success user='{}' ip='{}'", username, ip)
            countLogin("success")
            LoginResult.Success(user)
        } else {
            log.info("AUDIT: Login failed user='{}' ip='{}'", maskUsername(username), ip)
            countLogin("failed")
            LoginResult.Failed
        }
    }

    /**
     * Checks both per-IP and per-username failure counts.
     * Returns a RateLimited result with exponential backoff if either exceeds the threshold.
     */
    private fun checkRateLimit(ip: String, username: String): LoginResult.RateLimited? {
        val windowStart = LocalDateTime.now().minusMinutes(RATE_LIMIT_WINDOW_MINUTES)

        val ipFailures = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createQuery(
                """SELECT COUNT(*) FROM login_attempt
                   WHERE ip_address = :ip AND success = FALSE AND attempted_at > :window"""
            )
                .bind("ip", ip)
                .bind("window", windowStart)
                .mapTo(Int::class.java)
                .one()
        }

        val userFailures = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createQuery(
                """SELECT COUNT(*) FROM login_attempt
                   WHERE LOWER(username) = LOWER(:user) AND success = FALSE AND attempted_at > :window"""
            )
                .bind("user", username)
                .bind("window", windowStart)
                .mapTo(Int::class.java)
                .one()
        }

        val maxFailures = maxOf(ipFailures, userFailures)

        // Daily cap: hard limit on total failures per IP or username in 24 hours
        if (maxFailures < RATE_LIMIT_THRESHOLD) {
            val dayStart = LocalDateTime.now().minusHours(24)
            val dailyIpFailures = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    """SELECT COUNT(*) FROM login_attempt
                       WHERE ip_address = :ip AND success = FALSE AND attempted_at > :window"""
                )
                    .bind("ip", ip)
                    .bind("window", dayStart)
                    .mapTo(Int::class.java)
                    .one()
            }
            val dailyUserFailures = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    """SELECT COUNT(*) FROM login_attempt
                       WHERE LOWER(username) = LOWER(:user) AND success = FALSE AND attempted_at > :window"""
                )
                    .bind("user", username)
                    .bind("window", dayStart)
                    .mapTo(Int::class.java)
                    .one()
            }
            val maxDaily = maxOf(dailyIpFailures, dailyUserFailures)
            if (maxDaily >= DAILY_FAILURE_CAP) {
                log.info("AUDIT: Daily rate limit hit ip='{}' user='{}' ({} IP failures/24h, {} user failures/24h)",
                    ip, maskUsername(username), dailyIpFailures, dailyUserFailures)
                return LoginResult.RateLimited(RATE_LIMIT_MAX_COOLDOWN_SECONDS)
            }
            return null
        }

        // Permanent lockout after LOCKOUT_THRESHOLD failures — requires admin intervention
        if (maxFailures >= LOCKOUT_THRESHOLD) {
            val locked = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    "UPDATE app_user SET locked = TRUE, updated_at = :now WHERE LOWER(username) = LOWER(:u) AND locked = FALSE"
                )
                    .bind("now", LocalDateTime.now())
                    .bind("u", username)
                    .execute()
            }
            if (locked > 0) {
                log.warn("AUDIT: Account '{}' locked after {} failed attempts from ip='{}'",
                    maskUsername(username), maxFailures, ip)
            }
            return LoginResult.RateLimited(RATE_LIMIT_MAX_COOLDOWN_SECONDS)
        }

        // Exponential backoff: 30s, 60s, 120s, 240s, 480s, 900s (cap)
        val exponent = maxFailures - RATE_LIMIT_THRESHOLD
        val cooldown = minOf(
            RATE_LIMIT_BASE_COOLDOWN_SECONDS * (1L shl minOf(exponent, 10)),
            RATE_LIMIT_MAX_COOLDOWN_SECONDS
        )

        // Find the most recent failed attempt time for either dimension
        val lastAttempt = JdbiOrm.jdbi().withHandle<LocalDateTime?, Exception> { handle ->
            handle.createQuery(
                """SELECT MAX(attempted_at) FROM login_attempt
                   WHERE (ip_address = :ip OR LOWER(username) = LOWER(:user))
                     AND success = FALSE AND attempted_at > :window"""
            )
                .bind("ip", ip)
                .bind("user", username)
                .bind("window", windowStart)
                .mapTo(LocalDateTime::class.java)
                .firstOrNull()
        } ?: return null

        val retryAfter = java.time.Duration.between(LocalDateTime.now(), lastAttempt.plusSeconds(cooldown))
        val secondsRemaining = if (retryAfter.isNegative) 0L else retryAfter.seconds + 1
        if (secondsRemaining > 0) {
            log.info("AUDIT: Login rate-limited ip='{}' user='{}' ({} IP failures, {} user failures, {}s cooldown)",
                ip, maskUsername(username), ipFailures, userFailures, cooldown)
            return LoginResult.RateLimited(secondsRemaining)
        }
        return null
    }

    /**
     * Creates a new session token for the given user.
     * Returns the raw token string (caller is responsible for setting the cookie).
     */
    fun createSession(user: AppUser, userAgent: String): String {
        // Cap sessions per user — keep the most recent ones, trim the oldest
        val maxSessionsPerUser = 10
        val existingTokenIds = JdbiOrm.jdbi().withHandle<List<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT id FROM session_token WHERE user_id = :uid ORDER BY expires_at DESC"
            )
                .bind("uid", user.id)
                .mapTo(Long::class.java)
                .list()
        }
        if (existingTokenIds.size >= maxSessionsPerUser) {
            val toRemoveIds = existingTokenIds.drop(maxSessionsPerUser - 1) // keep 9, new one makes 10
            toRemoveIds.forEach { id -> SessionToken.findById(id)?.delete() }
            log.info("AUDIT: Session created user='{}' (trimmed {} oldest sessions)",
                user.username, toRemoveIds.size)
        } else {
            log.info("AUDIT: Session created user='{}'", user.username)
        }

        val token = UUID.randomUUID().toString()
        val tokenHash = hashToken(token)
        val now = LocalDateTime.now()
        SessionToken(
            user_id = user.id!!,
            token_hash = tokenHash,
            user_agent = userAgent,
            expires_at = now.plusDays(SESSION_DAYS),
            last_used_at = now
        ).save()
        return token
    }

    /**
     * Revokes a session by cookie token value. Returns the user who was logged out, or null.
     */
    fun revokeSessionByToken(cookieToken: String): AppUser? {
        val hash = hashToken(cookieToken)
        evictTokenCache(hash)
        val tokenId = JdbiOrm.jdbi().withHandle<Long?, Exception> { handle ->
            handle.createQuery("SELECT id FROM session_token WHERE token_hash = :hash LIMIT 1")
                .bind("hash", hash)
                .mapTo(Long::class.java)
                .firstOrNull()
        }
        val sessionToken = tokenId?.let { SessionToken.findById(it) }
        val user = sessionToken?.let { AppUser.findById(it.user_id) }
        sessionToken?.delete()
        return user
    }

    /**
     * Validates a session cookie token string directly (for Armeria auth decorator).
     */
    fun validateCookieToken(token: String): AppUser? = validateToken(token)

    private fun validateToken(token: String): AppUser? {
        val hash = hashToken(token)
        val now = Instant.now()

        // Check in-memory cache first (avoids 3 DB queries + 1 DB write per request)
        val cached = tokenCache[hash]
        if (cached != null && now.epochSecond - cached.cachedAt.epochSecond < TOKEN_CACHE_TTL_SECONDS) {
            // Throttle last_used_at updates: only write to DB every 5 minutes
            if (now.epochSecond - cached.lastUsedUpdatedAt.epochSecond >= LAST_USED_UPDATE_INTERVAL_SECONDS) {
                try {
                    JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                        handle.createUpdate("UPDATE session_token SET last_used_at = :now WHERE id = :id")
                            .bind("now", LocalDateTime.now())
                            .bind("id", cached.tokenId)
                            .execute()
                    }
                    tokenCache[hash] = cached.copy(lastUsedUpdatedAt = now)
                } catch (_: Exception) {
                    // Non-critical — don't fail the request over a timestamp update
                }
            }
            return cached.user
        }

        // Cache miss — validate against DB
        data class TokenLookup(val id: Long, val userId: Long)
        val lookup = JdbiOrm.jdbi().withHandle<TokenLookup?, Exception> { handle ->
            handle.createQuery(
                "SELECT id, user_id FROM session_token WHERE token_hash = :hash AND expires_at > :now LIMIT 1"
            )
                .bind("hash", hash)
                .bind("now", LocalDateTime.now())
                .map { rs, _ -> TokenLookup(rs.getLong("id"), rs.getLong("user_id")) }
                .firstOrNull()
        } ?: return null
        val user = AppUser.findById(lookup.userId) ?: return null

        // Populate cache
        tokenCache[hash] = CachedAuth(
            user = user,
            tokenId = lookup.id,
            cachedAt = now,
            lastUsedUpdatedAt = now,
        )

        // Update last_used_at on initial validation
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE session_token SET last_used_at = :now WHERE id = :id")
                .bind("now", LocalDateTime.now())
                .bind("id", lookup.id)
                .execute()
        }

        return user
    }

    /**
     * Builds a Set-Cookie header value for the session cookie.
     * Caller is responsible for adding this header to the HTTP response.
     */
    fun buildSessionCookieHeader(token: String, secure: Boolean): String {
        val maxAge = (SESSION_DAYS * 24 * 60 * 60).toInt()
        return buildString {
            append("${COOKIE_NAME}=$token; Path=/; Max-Age=$maxAge; HttpOnly; SameSite=Lax")
            if (secure) append("; Secure")
        }
    }

    /**
     * Builds a Set-Cookie header value that expires (clears) the session cookie.
     */
    fun buildExpireSessionCookieHeader(): String {
        return "${COOKIE_NAME}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
    }

    /**
     * Invalidates all sessions for the given user by deleting their session tokens.
     * Also revokes all device tokens. Call this when a password is changed/reset.
     */
    fun invalidateUserSessions(userId: Long) {
        evictTokenCacheForUser(userId)
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM session_token WHERE user_id = :uid")
                .bind("uid", userId)
                .execute()
        }
        if (deleted > 0) {
            log.info("AUDIT: Invalidated {} sessions for user='{}'", deleted, AppUser.usernameFor(userId))
        }
        PairingService.revokeAllForUser(userId)
        JwtService.revokeAllForUser(userId)
    }

    /**
     * Revokes a single browser session token by ID.
     * Returns false if the token doesn't exist, doesn't belong to [callerUserId],
     * or is the caller's current session.
     */
    fun revokeSession(tokenId: Long, callerUserId: Long, currentTokenHash: String?): Boolean {
        val token = SessionToken.findById(tokenId) ?: return false
        if (token.user_id != callerUserId) return false
        if (currentTokenHash != null && token.token_hash == currentTokenHash) return false
        evictTokenCache(token.token_hash)
        token.delete()
        log.info("AUDIT: Session token id={} revoked for user='{}'", tokenId, AppUser.usernameFor(token.user_id))
        return true
    }

    /**
     * Revokes all browser sessions for a user except the current one.
     */
    fun revokeAllSessionsExceptCurrent(userId: Long, currentTokenHash: String?) {
        // Evict all cached tokens for this user except the current one
        tokenCache.entries.removeIf { entry ->
            entry.value.user.id == userId && entry.key != currentTokenHash
        }
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            if (currentTokenHash != null) {
                handle.createUpdate(
                    "DELETE FROM session_token WHERE user_id = :uid AND token_hash != :hash"
                )
                    .bind("uid", userId)
                    .bind("hash", currentTokenHash)
                    .execute()
            } else {
                handle.createUpdate("DELETE FROM session_token WHERE user_id = :uid")
                    .bind("uid", userId)
                    .execute()
            }
        }
        if (deleted > 0) {
            log.info("AUDIT: Revoked {} sessions for user='{}' (kept current)", deleted, AppUser.usernameFor(userId))
        }
    }

    fun countAdmins(): Long = JdbiOrm.jdbi().withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) FROM app_user WHERE access_level >= 2")
            .mapTo(Long::class.java)
            .one()
    }

    fun cleanupExpiredTokens() {
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM session_token WHERE expires_at < :now")
                .bind("now", LocalDateTime.now())
                .execute()
        }
        if (deleted > 0) {
            log.info("Cleaned up {} expired session tokens", deleted)
        }
    }

    fun cleanupOldAttempts() {
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM login_attempt WHERE attempted_at < :cutoff")
                .bind("cutoff", LocalDateTime.now().minusDays(30))
                .execute()
        }
        if (deleted > 0) {
            log.info("Cleaned up {} old login attempts", deleted)
        }
    }
}
