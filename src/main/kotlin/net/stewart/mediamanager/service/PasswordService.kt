package net.stewart.mediamanager.service

import org.mindrot.jbcrypt.BCrypt

object PasswordService {

    const val MAX_PASSWORD_LENGTH = 128
    const val MIN_PASSWORD_LENGTH = 8

    // Pre-computed hash for dummy BCrypt verification (timing equalization)
    private val DUMMY_HASH = BCrypt.hashpw("dummy", BCrypt.gensalt(12))

    fun hash(plaintext: String): String =
        BCrypt.hashpw(plaintext, BCrypt.gensalt(12))

    fun verify(plaintext: String, hash: String): Boolean =
        BCrypt.checkpw(plaintext, hash)

    /**
     * Performs a dummy BCrypt verification to equalize timing when the user
     * does not exist. This prevents account enumeration via timing analysis.
     */
    fun dummyVerify() {
        BCrypt.checkpw("dummy", DUMMY_HASH)
    }

    /**
     * Validates a password against policy rules. Returns a list of violation messages
     * (empty if the password is acceptable).
     *
     * @param password the candidate password
     * @param username the user's username (password must not equal it)
     * @param currentHash if non-null, verifies the new password differs from the current one
     */
    fun validate(password: String, username: String, currentHash: String? = null): List<String> {
        val violations = mutableListOf<String>()
        if (password.length < MIN_PASSWORD_LENGTH) {
            violations.add("Must be at least $MIN_PASSWORD_LENGTH characters")
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            violations.add("Must be at most $MAX_PASSWORD_LENGTH characters")
        }
        if (password.equals(username, ignoreCase = true)) {
            violations.add("Password cannot be the same as your username")
        }
        if (currentHash != null && verify(password, currentHash)) {
            violations.add("New password must be different from current password")
        }
        return violations
    }
}
