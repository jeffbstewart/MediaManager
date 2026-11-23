import Foundation

/// What the CarPlay screen can show, decided from the phone's sign-in
/// state. CarPlay can't sign anyone in, so when the phone isn't signed
/// in it explains why in one line instead of showing tabs that fail.
public enum CarPlayAccess: Equatable, Sendable {
    /// The app is still restoring its session: show "Loading…".
    case waiting
    /// Signed in: show the music tabs.
    case ready
    /// Music can't be reached until something is done on the phone.
    case unavailable(CarPlayUnavailableReason)
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
}

public enum CarPlayAccessPolicy {
    /// `awaitingBiometric` is true when the phone holds a session but
    /// is waiting for Face ID before showing it. CarPlay launched in
    /// the background can't ask for Face ID, and it has always used
    /// the stored session in that state, so it counts as signed in.
    public static func access(for state: AuthManager.State, awaitingBiometric: Bool) -> CarPlayAccess {
        switch state {
        case .restoring:
            return .waiting
        case .authenticated:
            return .ready
        case .needsLogin:
            return awaitingBiometric ? .ready : .unavailable(.notSignedIn)
        case .needsServer, .needsSetup:
            return .unavailable(.notSignedIn)
        case .needsLegalAgreement:
            return .unavailable(.termsNotAccepted)
        case .fingerprintMismatch:
            return .unavailable(.serverChanged)
        }
    }
}
