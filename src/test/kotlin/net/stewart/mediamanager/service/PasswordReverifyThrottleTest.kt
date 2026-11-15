package net.stewart.mediamanager.service

import net.stewart.mediamanager.service.PasswordReverifyThrottle.Outcome
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PasswordReverifyThrottleTest {

    companion object {
        private const val RIGHT = "Correct-Horse-1"
        // One hash for the class: BCrypt at cost 12 is slow.
        private val HASH = PasswordService.hash(RIGHT)
    }

    private val clock = TestClock()

    @Before
    fun setUp() {
        PasswordReverifyThrottle.resetForTests()
        PasswordReverifyThrottle.clock = clock
    }

    @After
    fun tearDown() = PasswordReverifyThrottle.resetForTests()

    private fun attempt(password: String, userId: Long = 1L): Outcome =
        PasswordReverifyThrottle.verify(userId, "user$userId", password, HASH, "test")

    @Test
    fun `the right password verifies`() {
        assertEquals(Outcome.Verified, attempt(RIGHT))
    }

    @Test
    fun `below the threshold, wrong passwords are only wrong`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD - 1) { assertEquals(Outcome.WrongPassword, attempt("nope")) }
        assertEquals(Outcome.Verified, attempt(RIGHT))
    }

    @Test
    fun `reaching the threshold locks out every attempt, the right password included`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD) { assertEquals(Outcome.WrongPassword, attempt("nope")) }
        val locked = assertIs<Outcome.LockedOut>(attempt(RIGHT))
        assertEquals(PasswordReverifyThrottle.SHORT_LOCKOUT.seconds, locked.retryAfterSeconds)
        assertIs<Outcome.LockedOut>(attempt("nope"))
    }

    @Test
    fun `the lockout ends after its duration and attempts during it are not counted`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD) { attempt("nope") }
        repeat(10) { assertIs<Outcome.LockedOut>(attempt("nope")) }
        clock.advance(PasswordReverifyThrottle.SHORT_LOCKOUT.toMinutes())
        assertEquals(Outcome.Verified, attempt(RIGHT))
    }

    @Test
    fun `a correct password clears the history`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD - 1) { attempt("nope") }
        assertEquals(Outcome.Verified, attempt(RIGHT))
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD - 1) { assertEquals(Outcome.WrongPassword, attempt("nope")) }
        assertEquals(Outcome.Verified, attempt(RIGHT))
    }

    @Test
    fun `failures that age out of the short window do not count`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD - 1) { attempt("nope") }
        clock.advance(PasswordReverifyThrottle.SHORT_WINDOW.toMinutes() + 1)
        assertEquals(Outcome.WrongPassword, attempt("nope"))
        assertEquals(Outcome.Verified, attempt(RIGHT))
    }

    @Test
    fun `slow fishing hits the daily cap`() {
        // One guess every 16 minutes never trips the short window.
        repeat(PasswordReverifyThrottle.DAILY_THRESHOLD - 1) {
            assertEquals(Outcome.WrongPassword, attempt("nope"))
            clock.advance(16)
        }
        assertEquals(Outcome.WrongPassword, attempt("nope"))
        val locked = assertIs<Outcome.LockedOut>(attempt(RIGHT))
        assertEquals(PasswordReverifyThrottle.DAILY_LOCKOUT.seconds, locked.retryAfterSeconds)
    }

    @Test
    fun `one user's lockout does not touch another user`() {
        repeat(PasswordReverifyThrottle.SHORT_THRESHOLD) { attempt("nope", userId = 1L) }
        assertIs<Outcome.LockedOut>(attempt(RIGHT, userId = 1L))
        assertEquals(Outcome.Verified, attempt(RIGHT, userId = 2L))
    }

    @Test
    fun `lockout message rounds up to whole minutes or hours`() {
        assertEquals("Too many incorrect passwords. Try again in 1 minute.", PasswordReverifyThrottle.lockedOutMessage(5))
        assertEquals("Too many incorrect passwords. Try again in 15 minutes.", PasswordReverifyThrottle.lockedOutMessage(900))
        assertEquals("Too many incorrect passwords. Try again in 24 hours.", PasswordReverifyThrottle.lockedOutMessage(86_400))
    }
}
