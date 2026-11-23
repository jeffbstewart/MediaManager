import Foundation
import GRPCCore
import Testing
import MediaManagerCore

@Suite("Session recovery")
struct SessionRecoveryTests {

    // MARK: How a refresh ended

    @Test func onlyUnauthenticatedEndsTheSession() {
        #expect(TokenRefreshOutcome(refreshError: RPCError(code: .unauthenticated, message: "revoked"))
                == .sessionEnded)
    }

    @Test func networkFailuresKeepTheSession() {
        #expect(TokenRefreshOutcome(refreshError: RPCError(code: .unavailable, message: "no route"))
                == .unreachable)
        #expect(TokenRefreshOutcome(refreshError: RPCError(code: .deadlineExceeded, message: "timeout"))
                == .unreachable)
        #expect(TokenRefreshOutcome(refreshError: URLError(.notConnectedToInternet)) == .unreachable)
    }

    // MARK: A rejected access token

    @Test func refreshedTokensRetryTheCall() {
        #expect(SessionRecovery.afterRejectedAccessToken(refresh: .refreshed, alreadyRetried: false) == .retry)
    }

    @Test func anUnreachableServerDoesNotSignOut() {
        // The 2026-11-23 sign-out: the startup refresh couldn't reach the
        // server, the next call could, and its rejection of the expired
        // access token signed the phone out.
        #expect(SessionRecovery.afterRejectedAccessToken(refresh: .unreachable, alreadyRetried: false)
                == .staySignedIn)
        #expect(SessionRecovery.afterRejectedAccessToken(refresh: .unreachable, alreadyRetried: true)
                == .staySignedIn)
    }

    @Test func aRejectedRefreshHasAlreadySignedOut() {
        #expect(SessionRecovery.afterRejectedAccessToken(refresh: .sessionEnded, alreadyRetried: false)
                == .alreadySignedOut)
    }

    @Test func aFreshTokenRejectedAgainSignsOut() {
        // Retry once, not forever.
        #expect(SessionRecovery.afterRejectedAccessToken(refresh: .refreshed, alreadyRetried: true) == .signOut)
    }
}
