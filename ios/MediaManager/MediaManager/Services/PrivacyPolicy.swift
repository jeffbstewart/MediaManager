import Foundation

/// Reads the privacy policy URL injected at build time via Developer.xcconfig → Info.plist.
enum PrivacyPolicy {
    static var url: URL? {
        guard let urlString = Bundle.main.object(forInfoDictionaryKey: "PrivacyPolicyURL") as? String,
              !urlString.isEmpty,
              let url = URL(string: urlString) else {
            return nil
        }
        return url
    }
}
