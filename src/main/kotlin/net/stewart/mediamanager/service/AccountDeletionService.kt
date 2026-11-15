package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.AppUser
import org.slf4j.LoggerFactory

/**
 * Deletes an account: the one path both a user deleting themselves
 * (ProfileService/DeleteAccount) and an admin deleting someone else
 * (AdminService/DeleteUser, the admin REST endpoint) go through.
 *
 * What goes with the account is decided by the schema, not here: every
 * foreign key to `app_user` is ON DELETE CASCADE (V104), so the user's
 * wishes, progress, playlists (public ones included), flags, sessions,
 * device and refresh tokens and passkeys go with the row.  Household data
 * (tags, the Amazon order history, problem reports) does not reference a
 * user and is untouched.  Logs are not purged.
 *
 * The last admin can never be deleted: with no admin left, a server that
 * still has users could not be administered, and with no users at all it
 * would fall back to first-user setup, handing the whole house to the
 * next visitor.
 */
object AccountDeletionService {

    private val log = LoggerFactory.getLogger(AccountDeletionService::class.java)

    /** What the last admin is told when they try to delete their own account. */
    const val LAST_ADMIN_MESSAGE =
        "You are the only administrator, so this account cannot be deleted. " +
            "Make another user an administrator first. To remove everything, " +
            "shut down the server and delete its data."

    sealed interface Result {
        data class Deleted(val username: String) : Result
        data object NotFound : Result
        data object LastAdmin : Result
    }

    /** Serializes deletions so two concurrent ones cannot both pass the last-admin check. */
    private val lock = Any()

    fun delete(userId: Long): Result = synchronized(lock) {
        val user = AppUser.findById(userId) ?: return Result.NotFound
        if (user.isAdmin() && AuthService.countAdmins() <= 1) return Result.LastAdmin

        AuthService.invalidateUserSessions(userId)
        user.delete()
        // A request that raced the invalidation could have re-cached a
        // session for this user; the row is gone now, so evict once more.
        AuthService.evictTokenCacheForUser(userId)
        AuthService.invalidateHasUsersCache()

        log.info("AUDIT: account '{}' (id={}) deleted", user.username, userId)
        Result.Deleted(user.username)
    }
}
