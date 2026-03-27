import Foundation

/// Convenience accessors for server-provided legal document info.
/// URLs and versions come from the Discover RPC response, not build-time config.
extension MMLegalDocumentInfo {
    var privacyPolicyURL_resolved: URL? {
        hasPrivacyPolicyURL ? URL(string: privacyPolicyURL) : nil
    }

    var termsOfUseURL_resolved: URL? {
        hasTermsOfUseURL ? URL(string: termsOfUseURL) : nil
    }

    /// True if the server has configured both privacy policy and terms of use.
    var isConfigured: Bool {
        privacyPolicyURL_resolved != nil && termsOfUseURL_resolved != nil
    }
}
