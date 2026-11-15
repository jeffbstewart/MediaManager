import Foundation

/// Whether a user must choose a new password the next time they sign
/// in. An admin sets it when creating a user or resetting a password.
public enum PasswordChangeRequirement: Sendable, Equatable {
    /// The user must pick a new password before they can do anything else.
    case requiredAtNextSignIn
    /// The password the admin set stays in use.
    case notRequired

    /// For a toggle: on means `.requiredAtNextSignIn`.
    public init(isRequired: Bool) {
        self = isRequired ? .requiredAtNextSignIn : .notRequired
    }

    public var isRequired: Bool { self == .requiredAtNextSignIn }
}
