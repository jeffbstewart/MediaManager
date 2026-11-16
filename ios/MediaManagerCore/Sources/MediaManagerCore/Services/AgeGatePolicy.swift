import Foundation

/// What this device has learned about its owner's age, from Apple's
/// Declared Age Range API. Stored per device (not per account), so it
/// survives signing out and deleting an account, and is forgotten only
/// when the app is deleted.
///
/// Raw values are persisted: never rename one.
public enum AgeGateStatus: String, Sendable, CaseIterable {
    /// Apple says the owner is 18 or older.
    case adult
    /// The owner chose not to share their age.
    case declined
    /// The device can't answer (no age information for the account,
    /// or the service isn't offered here).
    case unavailable
    /// Apple says the owner is under 18: the app refuses service.
    case minor
}

/// What the age request produced, independent of Apple's types so the
/// decision can be tested anywhere.
public enum AgeGateAnswer: Sendable, Equatable {
    /// The owner shared an age range: `lowerBound` nil means below the
    /// lowest gate, `upperBound` nil means at or above the highest.
    case shared(lowerBound: Int?, upperBound: Int?)
    /// The owner declined to share, or declined the age setup.
    case declined
    /// The service can't answer for this device or account.
    case notAvailable
    /// A failure worth asking again about next launch (network, a
    /// request the system rejected, anything unexpected).
    case transientFailure
}

/// Defense in depth for an adults-only app: refuse service to people
/// Apple says are under 18. Not proof of age, and not meant to be;
/// declining, or a device that can't answer, is let through.
public enum AgeGatePolicy {
    /// The App Store rating is 18+; the gate matches it.
    public static let adultAge = 18

    /// What to store for `answer`, or nil to store nothing: let the
    /// person through this time and ask again next launch.
    public static func status(for answer: AgeGateAnswer) -> AgeGateStatus? {
        switch answer {
        case .shared(let lower, let upper):
            if let upper, upper < adultAge { return .minor }
            if let lower, lower >= adultAge { return .adult }
            // With a single gate at 18 the range is one side or the
            // other; anything else carries no usable answer.
            return .unavailable
        case .declined:
            return .declined
        case .notAvailable:
            return .unavailable
        case .transientFailure:
            return nil
        }
    }

    /// Whether to ask: only while nothing is stored.
    public static func shouldAsk(stored: AgeGateStatus?) -> Bool {
        stored == nil
    }

    /// Whether the app refuses service.
    public static func refusesService(stored: AgeGateStatus?) -> Bool {
        stored == .minor
    }
}
