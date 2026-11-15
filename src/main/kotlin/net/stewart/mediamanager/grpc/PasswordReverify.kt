package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.PasswordReverifyThrottle

/**
 * Checks the password a signed-in [user] re-entered for [action], through
 * [PasswordReverifyThrottle].  Returns normally when it is correct; throws
 * UNAUTHENTICATED when it is wrong, and RESOURCE_EXHAUSTED while the user
 * is locked out (the password is not checked then).
 */
internal fun requireReverifiedPassword(user: AppUser, password: String, action: String) {
    when (val outcome = PasswordReverifyThrottle.verify(user.id!!, user.username, password, user.password_hash, action)) {
        PasswordReverifyThrottle.Outcome.Verified -> return
        PasswordReverifyThrottle.Outcome.WrongPassword ->
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid current password"))
        is PasswordReverifyThrottle.Outcome.LockedOut ->
            throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription(
                "${PasswordReverifyThrottle.lockedOutMessage(outcome.retryAfterSeconds)} " +
                    "${AuthGrpcService.RETRY_AFTER} ${outcome.retryAfterSeconds} seconds"
            ))
    }
}
