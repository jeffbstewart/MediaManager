package net.stewart.mediamanager.service

import com.github.vokorm.count
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.server.VaadinRequest
import com.vaadin.flow.server.VaadinServletResponse
import com.vaadin.flow.server.VaadinSession
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LoginAttempt
import net.stewart.mediamanager.entity.SessionToken
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

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

    private const val SESSION_KEY = "currentUser"
    private const val CURRENT_TOKEN_HASH_KEY = "currentTokenHash"
    private const val COOKIE_NAME = "mm_auth"
    private const val SESSION_DAYS = 30L
    private const val RATE_LIMIT_WINDOW_MINUTES = 15L
    private const val RATE_LIMIT_THRESHOLD = 5
    private const val RATE_LIMIT_BASE_COOLDOWN_SECONDS = 30L
    private const val RATE_LIMIT_MAX_COOLDOWN_SECONDS = 900L // 15 minutes
    private const val LOCKOUT_THRESHOLD = 20
    private const val DAILY_FAILURE_CAP = 100

    fun hasUsers(): Boolean = AppUser.count() > 0

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

    fun establishSession(user: AppUser): String {
        // Rotate HTTP session ID to prevent session fixation
        val servletRequest = (VaadinRequest.getCurrent() as? com.vaadin.flow.server.VaadinServletRequest)?.httpServletRequest
        servletRequest?.changeSessionId()

        val userAgent = servletRequest?.getHeader("User-Agent") ?: ""

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

        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, user)
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
        VaadinSession.getCurrent()?.setAttribute(CURRENT_TOKEN_HASH_KEY, tokenHash)
        return token
    }

    fun logout() {
        val user = getCurrentUser()
        // Delete the current session token from DB
        val request = VaadinRequest.getCurrent()
        val cookieValue = request?.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value
        if (cookieValue != null) {
            val hash = hashToken(cookieValue)
            val tokenId = JdbiOrm.jdbi().withHandle<Long?, Exception> { handle ->
                handle.createQuery("SELECT id FROM session_token WHERE token_hash = :hash LIMIT 1")
                    .bind("hash", hash)
                    .mapTo(Long::class.java)
                    .firstOrNull()
            }
            tokenId?.let { SessionToken.findById(it)?.delete() }
        }
        // Expire the HttpOnly cookie via Set-Cookie header
        val response = VaadinServletResponse.getCurrent()
        if (response != null) {
            val expireCookie = Cookie(COOKIE_NAME, "").apply {
                path = "/"
                maxAge = 0
                isHttpOnly = true
            }
            response.addCookie(expireCookie)
        }
        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, null)
        if (user != null) {
            val ip = extractIpFromVaadinRequest()
            log.info("AUDIT: Logout user='{}' ip='{}'", user.username, ip ?: "unknown")
        }
    }

    private fun extractIpFromVaadinRequest(): String? {
        val request = VaadinRequest.getCurrent() ?: return null
        val httpRequest = (request as? com.vaadin.flow.server.VaadinServletRequest)?.httpServletRequest
        return httpRequest?.remoteAddr
    }

    fun getCurrentUser(): AppUser? =
        VaadinSession.getCurrent()?.getAttribute(SESSION_KEY) as? AppUser

    /**
     * Re-fetches the current user from DB and updates the session cache.
     * Returns null if the user no longer exists (was deleted).
     */
    fun refreshCurrentUser(): AppUser? {
        val cached = getCurrentUser() ?: return null
        val fresh = AppUser.findById(cached.id!!) ?: return null
        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, fresh)
        return fresh
    }

    /**
     * Attempts to restore a session from the mm_session cookie in the current VaadinRequest.
     * Returns the user if the cookie token is valid and not expired.
     */
    fun restoreFromCookie(): AppUser? {
        val request = VaadinRequest.getCurrent() ?: return null
        val cookieValue = request.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value ?: return null
        return validateToken(cookieValue)
    }

    /**
     * Validates a cookie token from a raw HttpServletRequest (for servlet filter context).
     */
    fun validateCookieFromRequest(request: HttpServletRequest): AppUser? {
        val cookieValue = request.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value ?: return null
        return validateToken(cookieValue)
    }

    private fun validateToken(token: String): AppUser? {
        val hash = hashToken(token)
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
        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, user)
        // Update last_used_at
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE session_token SET last_used_at = :now WHERE id = :id")
                .bind("now", LocalDateTime.now())
                .bind("id", lookup.id)
                .execute()
        }
        // Store current token hash in session so the view can identify "this" session
        VaadinSession.getCurrent()?.setAttribute(CURRENT_TOKEN_HASH_KEY, hash)
        return user
    }

    /**
     * Sets the mm_session cookie server-side with HttpOnly, SameSite=Lax, and
     * conditionally Secure. This prevents client-side JS from reading the token.
     */
    fun setSessionCookie(token: String) {
        val response = VaadinServletResponse.getCurrent() ?: return
        val request = VaadinRequest.getCurrent()
        val isSecure = request?.isSecure == true
            || request?.getHeader("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true
        val maxAge = (SESSION_DAYS * 24 * 60 * 60).toInt()
        val header = buildString {
            append("${COOKIE_NAME}=$token; Path=/; Max-Age=$maxAge; HttpOnly; SameSite=Lax")
            if (isSecure) append("; Secure")
        }
        response.addHeader("Set-Cookie", header)
    }

    /**
     * Invalidates all sessions for the given user by deleting their session tokens.
     * Also revokes all device tokens. Call this when a password is changed/reset.
     */
    fun invalidateUserSessions(userId: Long) {
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM session_token WHERE user_id = :uid")
                .bind("uid", userId)
                .execute()
        }
        if (deleted > 0) {
            log.info("AUDIT: Invalidated {} sessions for user_id={}", deleted, userId)
        }
        PairingService.revokeAllForUser(userId)
        JwtService.revokeAllForUser(userId)
    }

    fun getCurrentTokenHash(): String? {
        var hash = VaadinSession.getCurrent()?.getAttribute(CURRENT_TOKEN_HASH_KEY) as? String
        if (hash == null) {
            // Derive from cookie for sessions established before token-hash tracking was added
            val request = VaadinRequest.getCurrent() ?: return null
            val cookieValue = request.cookies
                ?.firstOrNull { it.name == COOKIE_NAME }
                ?.value ?: return null
            hash = hashToken(cookieValue)
            VaadinSession.getCurrent()?.setAttribute(CURRENT_TOKEN_HASH_KEY, hash)
        }
        return hash
    }

    /**
     * Revokes a single browser session token by ID.
     * Returns false if the token is the caller's current session.
     */
    fun revokeSession(tokenId: Long, currentTokenHash: String?): Boolean {
        val token = SessionToken.findById(tokenId) ?: return false
        if (currentTokenHash != null && token.token_hash == currentTokenHash) return false
        token.delete()
        log.info("AUDIT: Session token id={} revoked for user_id={}", tokenId, token.user_id)
        return true
    }

    /**
     * Revokes all browser sessions for a user except the current one.
     */
    fun revokeAllSessionsExceptCurrent(userId: Long, currentTokenHash: String?) {
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
            log.info("AUDIT: Revoked {} sessions for user_id={} (kept current)", deleted, userId)
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
