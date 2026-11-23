import Foundation

/// What the CarPlay screen can show, decided from the phone's sign-in
/// state and whether it can reach the server. CarPlay can't sign anyone
/// in, so when there is no music to offer it explains why in one line
/// instead of showing tabs that fail.
public enum CarPlayAccess: Equatable, Sendable {
    /// The app is still restoring its session: show "Loading…".
    case waiting
    /// Show the music tabs, filled from `source`.
    case ready(CarPlayMusicSource)
    /// There's no music to offer, for this reason.
    case unavailable(CarPlayUnavailableReason)
}

/// Where the CarPlay tabs get their lists. When it changes mid-drive
/// the tabs are rebuilt, so they never list music that can't play.
public enum CarPlayMusicSource: Equatable, Sendable {
    /// The server's library: online.
    case server
    /// Music downloaded to the phone: Offline Mode is on, or there's
    /// no network. The app's data model switches to its offline
    /// content in both cases (`DownloadManager.isEffectivelyOffline`).
    case downloads
}

/// Why CarPlay has no music to offer. Only the reason lives here; the
/// words for it belong to the CarPlay UI (the app's string catalog).
public enum CarPlayUnavailableReason: Equatable, Sendable, CaseIterable {
    /// No account is signed in, or no server is set up.
    case notSignedIn
    /// Signed in, but the server's terms haven't been accepted.
    case termsNotAccepted
    /// The server no longer matches the one this phone trusted.
    case serverChanged
    /// Offline, and nothing is downloaded to play.
    case offlineNothingDownloaded
}

public enum CarPlayAccessPolicy {
    /// `awaitingBiometric` is true when the phone holds a session but
    /// is waiting for Face ID before showing it. CarPlay launched in
    /// the background can't ask for Face ID, and it has always used
    /// the stored session in that state, so it counts as signed in.
    ///
    /// `offline` is `DownloadManager.isEffectivelyOffline`, and
    /// `hasDownloadedMusic` says whether any album or playlist is
    /// downloaded. They matter only once signed in: a signed-out phone
    /// has no music either way, and signing in is the thing to explain.
    public static func access(
        for state: AuthManager.State, awaitingBiometric: Bool,
        offline: Bool, hasDownloadedMusic: Bool
    ) -> CarPlayAccess {
        let signedIn: CarPlayAccess = switch (offline, hasDownloadedMusic) {
        case (false, _): .ready(.server)
        case (true, true): .ready(.downloads)
        case (true, false): .unavailable(.offlineNothingDownloaded)
        }
        switch state {
        case .restoring:
            return .waiting
        case .authenticated:
            return signedIn
        case .needsLogin:
            return awaitingBiometric ? signedIn : .unavailable(.notSignedIn)
        case .needsServer, .needsSetup:
            return .unavailable(.notSignedIn)
        case .needsLegalAgreement:
            return .unavailable(.termsNotAccepted)
        case .fingerprintMismatch:
            return .unavailable(.serverChanged)
        }
    }
}
