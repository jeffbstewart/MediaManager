package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime

/**
 * Throttles the endpoints where an already-signed-in user re-enters their
 * current password: delete-account and change-password, over both REST and
 * gRPC.  Without it, anyone holding a stolen session (or a borrowed,
 * unlocked phone) could guess the password there without limit, and a
 * confirmed password is worth more than the session -- it is reused on
 * other sites, and it survives the session being revoked.
 *
 * The key is the user id, never the IP.  Each of these endpoints only ever
 * checks the caller's own password, so the user is the thing being
 * attacked, and an attacker cannot dodge the limit by changing address.
 * All four endpoints share one counter, so fishing cannot hop from one to
 * another.
 *
 * Policy:
 * - [SHORT_THRESHOLD] wrong passwords within [SHORT_WINDOW] lock the user
 *   out of these endpoints for [SHORT_LOCKOUT].
 * - [DAILY_THRESHOLD] wrong passwords within [DAILY_WINDOW] lock them out
 *   for [DAILY_LOCKOUT], which caps a patient attacker who waits out each
 *   short lockout.
 * - While locked out, every attempt is refused before the password is
 *   checked, so a correct guess during a lockout reveals nothing; refused
 *   attempts are not counted.
 * - A correct password clears the user's history.
 *
 * Only these endpoints are locked; sign-in, browsing and playback are not
 * affected.  The state is in memory, so a server restart clears it.  That
 * is acceptable because an attacker holding only a session cannot restart
 * the server.
 */
object PasswordReverifyThrottle {

    private val log = LoggerFactory.getLogger(PasswordReverifyThrottle::class.java)

    internal const val SHORT_THRESHOLD = 5
    internal val SHORT_WINDOW: Duration = Duration.ofMinutes(15)
    internal val SHORT_LOCKOUT: Duration = Duration.ofMinutes(15)
    internal const val DAILY_THRESHOLD = 20
    internal val DAILY_WINDOW: Duration = Duration.ofHours(24)
    internal val DAILY_LOCKOUT: Duration = Duration.ofHours(24)

    sealed interface Outcome {
        data object Verified : Outcome
        data object WrongPassword : Outcome
        data class LockedOut(val retryAfterSeconds: Long) : Outcome
    }

    private class State {
        val failures = ArrayDeque<LocalDateTime>()
        var lockedUntil: LocalDateTime? = null
    }

    private val states = HashMap<Long, State>()
    private val lock = Any()

    @Volatile
    internal var clock: Clock = SystemClock

    /** User-facing text for a lockout; the same on every endpoint. */
    fun lockedOutMessage(retryAfterSeconds: Long): String {
        val minutes = (retryAfterSeconds + 59) / 60
        val wait = when {
            minutes >= 120 -> "${(minutes + 59) / 60} hours"
            minutes == 1L -> "1 minute"
            else -> "$minutes minutes"
        }
        return "Too many incorrect passwords. Try again in $wait."
    }

    /**
     * Checks [password] against [passwordHash] for [userId], unless the user
     * is locked out, in which case the password is not checked at all.
     * [username] and [action] go into the audit log only.
     */
    fun verify(userId: Long, username: String, password: String, passwordHash: String, action: String): Outcome {
        lockedOutFor(userId)?.let { seconds ->
            log.warn("AUDIT: {} refused for user '{}': password re-entry locked out, {}s left", action, username, seconds)
            return Outcome.LockedOut(seconds)
        }
        // BCrypt runs outside the lock; two racing wrong guesses can each
        // pass the check above, which overshoots the threshold by at most
        // the number of concurrent requests.
        if (PasswordService.verify(password, passwordHash)) {
            synchronized(lock) { states.remove(userId) }
            return Outcome.Verified
        }
        recordFailure(userId, username, action)
        return Outcome.WrongPassword
    }

    private fun lockedOutFor(userId: Long): Long? = synchronized(lock) {
        val until = states[userId]?.lockedUntil ?: return null
        val now = clock.now()
        if (!now.isBefore(until)) return null
        maxOf(1L, Duration.between(now, until).seconds)
    }

    private fun recordFailure(userId: Long, username: String, action: String) = synchronized(lock) {
        val now = clock.now()
        val state = states.getOrPut(userId) { State() }
        state.failures.addLast(now)
        while (state.failures.first().isBefore(now.minus(DAILY_WINDOW))) state.failures.removeFirst()

        val inShortWindow = state.failures.count { !it.isBefore(now.minus(SHORT_WINDOW)) }
        val lockout = when {
            state.failures.size >= DAILY_THRESHOLD -> DAILY_LOCKOUT
            inShortWindow >= SHORT_THRESHOLD -> SHORT_LOCKOUT
            else -> null
        }
        if (lockout != null) {
            state.lockedUntil = now.plus(lockout)
            log.warn("AUDIT: wrong password on {} for user '{}' ({} in {} min, {} in {} h): locked out for {} min",
                action, username, inShortWindow, SHORT_WINDOW.toMinutes(), state.failures.size,
                DAILY_WINDOW.toHours(), lockout.toMinutes())
        } else {
            log.info("AUDIT: wrong password on {} for user '{}' ({} in {} min)",
                action, username, inShortWindow, SHORT_WINDOW.toMinutes())
        }
    }

    internal fun resetForTests() = synchronized(lock) {
        states.clear()
        clock = SystemClock
    }
}
