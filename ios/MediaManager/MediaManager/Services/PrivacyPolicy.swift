import Foundation

/// Reads legal document URLs and versions injected at build time
/// via secrets/ios.agent_visible_env → ios-build.sh → Info.plist.
enum LegalDocuments {
    static var privacyPolicyURL: URL? {
        urlFromInfoPlist("PrivacyPolicyURL")
    }

    static var privacyPolicyVersion: Int {
        intFromInfoPlist("PrivacyPolicyVersion")
    }

    static var termsOfUseURL: URL? {
        urlFromInfoPlist("TermsOfUseURL")
    }

    static var termsOfUseVersion: Int {
        intFromInfoPlist("TermsOfUseVersion")
    }

    /// True if both privacy policy and terms of use are configured.
    static var isConfigured: Bool {
        privacyPolicyURL != nil && termsOfUseURL != nil
    }

    private static func urlFromInfoPlist(_ key: String) -> URL? {
        guard let str = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !str.isEmpty,
              let url = URL(string: str) else { return nil }
        return url
    }

    private static func intFromInfoPlist(_ key: String) -> Int {
        if let str = Bundle.main.object(forInfoDictionaryKey: key) as? String {
            return Int(str) ?? 0
        }
        return 0
    }
}

// Backward compatibility alias
enum PrivacyPolicy {
    static var url: URL? { LegalDocuments.privacyPolicyURL }
}
