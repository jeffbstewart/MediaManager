import Foundation
import GRPCCore
import MediaManagerProtos

/// A call the server's `AuthInterceptor` refused because the account
/// owes something before it may continue. The interceptor closes the
/// call with PERMISSION_DENIED and one of two fixed descriptions.
public enum GateRefusal: Sendable, Equatable {
    case legalAgreementRequired
    case passwordChangeRequired

    /// Wire contract: `AuthInterceptor.LEGAL_AGREEMENT_REQUIRED` on the
    /// server. Never change it without changing the server.
    public static let legalAgreementDescription = "legal_agreement_required"
    /// Wire contract: the description `AuthInterceptor` sends for
    /// `must_change_password`. Never change it without changing the server.
    public static let passwordChangeDescription = "password_change_required"

    /// The refusal `error` carries, or nil for any other error.
    public init?(_ error: Error) {
        guard let rpc = error as? RPCError, rpc.code == .permissionDenied else { return nil }
        switch rpc.message.trimmingCharacters(in: .whitespacesAndNewlines) {
        case Self.legalAgreementDescription: self = .legalAgreementRequired
        case Self.passwordChangeDescription: self = .passwordChangeRequired
        default: return nil
        }
    }
}

/// What `GrpcClient` throws in place of a gate refusal. By the time a
/// view sees it, `AuthManager` is already bringing up the screen that
/// resolves it, so the text only has to make sense for the moment
/// before that screen covers the view.
public struct GateRefusalError: LocalizedError, Sendable, Equatable {
    public let refusal: GateRefusal

    public init(_ refusal: GateRefusal) { self.refusal = refusal }

    public var errorDescription: String? {
        switch refusal {
        case .legalAgreementRequired:
            "The server needs you to accept its terms before continuing."
        case .passwordChangeRequired:
            "You need to change your password before continuing."
        }
    }
}

/// The legal versions a user agreed to in this session.
public struct AgreedLegalVersions: Sendable, Equatable {
    public let privacyPolicy: Int32
    public let termsOfUse: Int32

    public init(privacyPolicy: Int32, termsOfUse: Int32) {
        self.privacyPolicy = privacyPolicy
        self.termsOfUse = termsOfUse
    }
}

/// What to do when a call is refused with `legal_agreement_required`,
/// given the legal status fetched right after the refusal.
public enum LegalGateDecision: Sendable, Equatable {
    /// The server wants versions the user has not agreed to: show the
    /// terms screen, as at sign-in.
    case presentTerms
    /// Agreeing again would not help, so say so and offer sign out
    /// instead of looping. Either the status says the account complies
    /// while the gate refuses it, or the gate still refuses after the
    /// user agreed to exactly the versions the server asks for. Both
    /// mean the server's terms setup needs its admin.
    case stuck

    public static func afterRefusal(
        status: MMLegalStatusResponse,
        lastAgreed: AgreedLegalVersions?
    ) -> LegalGateDecision {
        if status.compliant { return .stuck }
        let required = AgreedLegalVersions(
            privacyPolicy: status.requiredPrivacyPolicyVersion,
            termsOfUse: status.requiredTermsOfUseVersion)
        if let lastAgreed, lastAgreed == required { return .stuck }
        return .presentTerms
    }

    public static let stuckMessage = """
        You agreed to the server's terms, but it still won't let this \
        app continue. Its terms setup needs attention from the server's \
        admin. Sign out for now, and try again once they've looked at it.
        """

    public static let statusUnavailableMessage = """
        The server asked you to accept its terms, but they couldn't be \
        loaded. Check your connection and try again.
        """
}

/// What `AuthManager.changePassword` throws. The server's ChangePassword
/// descriptions are written for people ("Invalid current password", the
/// password rule that failed), so they are shown as they come.
public struct PasswordChangeError: LocalizedError, Sendable, Equatable {
    public let message: String

    public init(message: String) { self.message = message }

    public init(_ error: Error) {
        guard let rpc = error as? RPCError else {
            self.message = error.localizedDescription
            return
        }
        switch rpc.code {
        case .unauthenticated:
            self.message = rpc.message.isEmpty ? "The current password is incorrect." : rpc.message
        case .invalidArgument:
            self.message = rpc.message.isEmpty ? "The new password was not accepted." : rpc.message
        default:
            self.message = RPCError.userMessage(for: rpc)
        }
    }

    public var errorDescription: String? { message }

    /// Shown when the server still demands a password change after one
    /// succeeded in this session.
    public static let stillRequiredMessage = """
        Your password was changed, but the server still asks for a new \
        one. Sign out and sign in with your new password. If this keeps \
        happening, the server's admin needs to check your account.
        """
}
