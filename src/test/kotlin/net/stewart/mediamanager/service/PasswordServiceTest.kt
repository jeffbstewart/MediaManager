package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BCrypt cost factor 12 — each hash takes ~100ms. Keep this suite
 * small (a handful of hash() calls) so the test stays fast.
 */
class PasswordServiceTest {

    @Test
    fun `hash and verify round-trip`() {
        val hash = PasswordService.hash("hunter2!")
        assertTrue(PasswordService.verify("hunter2!", hash))
    }

    @Test
    fun `verify rejects wrong password`() {
        val hash = PasswordService.hash("correct horse battery staple")
        assertFalse(PasswordService.verify("wrong", hash))
        assertFalse(PasswordService.verify("CORRECT HORSE BATTERY STAPLE", hash))
    }

    @Test
    fun `hash produces different output for same input`() {
        // BCrypt salts each hash, so two calls with the same password
        // must produce different cipher text. Both still verify.
        val a = PasswordService.hash("same-input")
        val b = PasswordService.hash("same-input")
        assertNotEquals(a, b)
        assertTrue(PasswordService.verify("same-input", a))
        assertTrue(PasswordService.verify("same-input", b))
    }

    @Test
    fun `dummyVerify does not throw`() {
        // The timing-equalisation helper has no return; just exercise
        // that the path runs and the precomputed hash is valid.
        PasswordService.dummyVerify()
    }

    // ---------------------- validate ----------------------

    @Test
    fun `validate empty list when password meets policy`() {
        val v = PasswordService.validate("a-good-password", "alice")
        assertTrue(v.isEmpty())
    }

    @Test
    fun `validate rejects short passwords`() {
        val v = PasswordService.validate("short", "alice")
        assertEquals(1, v.size)
        assertContains(v[0], "${PasswordService.MIN_PASSWORD_LENGTH} characters")
    }

    @Test
    fun `validate rejects long passwords`() {
        val tooLong = "x".repeat(PasswordService.MAX_PASSWORD_LENGTH + 1)
        val v = PasswordService.validate(tooLong, "alice")
        assertTrue(v.any { it.contains("at most") })
    }

    @Test
    fun `validate rejects username-equals-password case-insensitively`() {
        val v1 = PasswordService.validate("alice123", "alice")
        // Length is fine, but username check passes since "alice123" != "alice".
        assertTrue(v1.isEmpty(), "different strings should be allowed")

        val v2 = PasswordService.validate("alice", "alice")
        assertTrue(v2.any { it.contains("same as your username") },
            "exact match should be rejected")

        val v3 = PasswordService.validate("Alice", "alice")
        assertTrue(v3.any { it.contains("same as your username") },
            "case-insensitive match should be rejected")
    }

    @Test
    fun `validate rejects unchanged password when current hash supplied`() {
        val current = PasswordService.hash("not_changed")
        val v = PasswordService.validate("not_changed", "alice", current)
        assertTrue(v.any { it.contains("different from current") })
    }

    @Test
    fun `validate accepts new password when it differs from current`() {
        val current = PasswordService.hash("not_changed")
        val v = PasswordService.validate("definitely-different", "alice", current)
        assertTrue(v.isEmpty())
    }

    @Test
    fun `validate accumulates multiple violations`() {
        // Short AND username-match → both messages.
        val v = PasswordService.validate("alice", "alice")
        assertEquals(2, v.size)
    }
}
