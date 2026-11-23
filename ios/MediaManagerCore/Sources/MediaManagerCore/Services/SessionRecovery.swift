import Foundation
import GRPCCore

/// How a token refresh ended.
public enum TokenRefreshOutcome: Sendable, Equatable {
    /// The server issued new tokens.
    case refreshed
    /// The server rejected the refresh token, or none was stored: the
    /// session is over and the phone has signed out.
    case sessionEnded
    /// The refresh didn't get an answer (no network, VPN not up yet,
    /// server down). The stored tokens are kept for the next attempt.
    case unreachable

    /// The outcome of a refresh call that threw `error`. Only an
    /// UNAUTHENTICATED answer ends the session: the server decides
    /// that, and nothing else can.
    public init(refreshError error: Error) {
        if let rpc = error as? RPCError, rpc.code == .unauthenticated {
            self = .sessionEnded
        } else {
            self = .unreachable
        }
    }
}

/// What to do when the server rejects the phone's access token.
///
/// An access token lasts minutes; rejection usually means it expired
/// while a refresh couldn't reach the server, for example when the app
/// starts before the network is up. That is no reason to sign out (#101):
/// only a rejected *refresh* ends a session. So refresh, and decide by
/// how the refresh went.
public enum SessionRecovery {

    public enum Action: Sendable, Equatable {
        /// New tokens: try the call again.
        case retry
        /// The refresh ended the session; it has signed out already.
        case alreadySignedOut
        /// The server can't be reached: stay signed in with the stored
        /// tokens; the next scheduled refresh tries again.
        case staySignedIn
        /// Even a freshly issued token was rejected: sign out.
        case signOut
    }

    /// `alreadyRetried` is true when the rejected call was itself the
    /// retry after a refresh.
    public static func afterRejectedAccessToken(
        refresh: TokenRefreshOutcome, alreadyRetried: Bool
    ) -> Action {
        switch refresh {
        case .refreshed: alreadyRetried ? .signOut : .retry
        case .sessionEnded: .alreadySignedOut
        case .unreachable: .staySignedIn
        }
    }
}
