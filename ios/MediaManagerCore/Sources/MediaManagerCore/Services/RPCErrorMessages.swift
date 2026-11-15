import Foundation
import GRPCCore

/// `RPCError` has no `LocalizedError` conformance of its own, so every
/// view that showed `error.localizedDescription` for a failed call put
/// "The operation couldn't be completed. (GRPCCore.RPCError 1.)" on
/// screen. Conforming here gives every such view a sentence the user
/// can act on, with no change at the call sites.
extension RPCError: @retroactive LocalizedError {
    public var errorDescription: String? { Self.userMessage(for: self) }

    /// A sentence for the user. The server's own description is included
    /// only for codes where the server writes it for people (a rejected
    /// value, a conflict); for the rest it is developer text.
    public static func userMessage(for error: RPCError) -> String {
        let serverText = error.message.trimmingCharacters(in: .whitespacesAndNewlines)
        switch error.code {
        case .invalidArgument, .failedPrecondition, .alreadyExists, .outOfRange:
            return serverText.isEmpty ? "The server didn't accept that request." : serverText
        case .unauthenticated:
            return "Your session has expired. Sign out and sign in again."
        case .permissionDenied:
            return "The server refused this request. Your account may not have access to it."
        case .notFound:
            return "The server couldn't find that. It may have been removed."
        case .unavailable:
            return "Can't reach the server. Check your connection and try again."
        case .deadlineExceeded:
            return "The server took too long to answer. Try again."
        case .resourceExhausted:
            return "The server is busy. Wait a moment and try again."
        case .cancelled:
            return "The request was cancelled."
        case .unimplemented:
            return "The server doesn't support this yet. It may need updating."
        default:
            return "Something went wrong on the server. Try again, and if it "
                + "keeps happening, tell the server's admin."
        }
    }
}
