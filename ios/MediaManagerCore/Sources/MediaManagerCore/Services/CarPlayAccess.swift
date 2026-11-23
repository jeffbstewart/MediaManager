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

/// Why CarPlay has no music to offer. Each one tells the driver what
/// happened and that it waits until they are not driving: CarPlay must
/// never ask a driver to pick up the phone.
public enum CarPlayUnavailableReason: Equatable, Sendable, CaseIterable {
    /// No account is signed in, or no server is set up.
    case notSignedIn
    /// Signed in, but the server's terms haven't been accepted.
    case termsNotAccepted
    /// The server no longer matches the one this phone trusted.
    case serverChanged

    /// The row's main line.
    public var title: String {
        switch self {
        case .notSignedIn: "Not signed in"
        case .termsNotAccepted: "Server terms not accepted"
        case .serverChanged: "Server changed"
        }
    }

    /// The row's second line.
    public var detail: String {
        switch self {
        case .notSignedIn: "Sign in on your phone when you're not driving."
        case .termsNotAccepted: "Accept them on your phone when you're not driving."
        case .serverChanged: "Check the server on your phone when you're not driving."
        }
    }
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
