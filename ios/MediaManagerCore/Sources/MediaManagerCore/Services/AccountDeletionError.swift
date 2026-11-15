import Foundation
import GRPCCore

/// What `AuthManager.deleteAccount` throws: why the server did not
/// delete the account, in words fit to show the user.
public struct AccountDeletionError: LocalizedError, Sendable, Equatable {
    public let message: String

    public init(message: String) { self.message = message }

    public init(_ error: Error) {
        guard let rpc = error as? RPCError else {
            self.message = error.localizedDescription
            return
        }
        let serverText = rpc.message.trimmingCharacters(in: .whitespacesAndNewlines)
        switch rpc.code {
        case .unauthenticated:
            // "Invalid current password" from the server.
            self.message = "That password is incorrect."
        case .invalidArgument:
            self.message = "Enter your current password."
        case .resourceExhausted:
            // The throttle's sentence, followed by a machine-readable
            // "Retry after N seconds" for clients that count down.
            self.message = Self.withoutRetryAfter(serverText)
                ?? "Too many incorrect passwords. Try again later."
        case .failedPrecondition:
            // The last admin; the server says what to do instead.
            self.message = serverText.isEmpty
                ? "The server can't delete this account right now."
                : serverText
        case .unimplemented:
            self.message = "This server can't delete accounts yet. Ask its admin to update it."
        default:
            self.message = RPCError.userMessage(for: rpc)
        }
    }

    public var errorDescription: String? { message }

    /// Wire contract: `AuthGrpcService.RETRY_AFTER` on the server.
    static let retryAfterMarker = "Retry after"

    static func withoutRetryAfter(_ text: String) -> String? {
        let sentence: Substring
        if let range = text.range(of: retryAfterMarker) {
            sentence = text[..<range.lowerBound]
        } else {
            sentence = Substring(text)
        }
        let trimmed = sentence.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
