import Foundation
import MediaManagerProtos

/// Convenience accessors for server-provided legal document info.
/// URLs and versions come from the Discover RPC response, not build-time config.
public extension MMLegalDocumentInfo {
    public var privacyPolicyURL_resolved: URL? {
        hasPrivacyPolicyURL ? URL(string: privacyPolicyURL) : nil
    }

    public var termsOfUseURL_resolved: URL? {
        hasTermsOfUseURL ? URL(string: termsOfUseURL) : nil
    }

    /// True if the server has configured both privacy policy and terms of use.
    public var isConfigured: Bool {
        privacyPolicyURL_resolved != nil && termsOfUseURL_resolved != nil
    }
}
