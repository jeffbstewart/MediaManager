import Foundation
import Security
import MediaManagerProtos

public enum KeychainService {
    private static let serviceName = "net.stewart.mediamanager"

    public enum Key: String, Sendable {
        /// The server's canonical address — one host and one port,
        /// covering gRPC, images, and streaming alike, because Armeria
        /// serves all of them on a single port.
        case serverURL = "server_url"
        /// DEPRECATED — written by pre-v2 builds, which kept a separate
        /// HTTP base URL alongside the gRPC one. Never read anymore;
        /// the raw value stays "http_base_url" so the endpoint purge
        /// can still delete the stale row. Remove once no install can
        /// plausibly still be carrying it.
        case deprecatedHttpBaseURL = "http_base_url"
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case serverFingerprint = "server_fingerprint"
        /// Stamped alongside serverURL. See AuthManager.serverConfigVersion.
        case serverConfigVersion = "server_config_version"
    }

    /// Keys describing *where* the server is, as opposed to who the
    /// user is. Dropped together by AuthManager.changeServer() so a
    /// re-point keeps the session tokens (which the server issued and
    /// which are not bound to its address).
    public static let endpointKeys: [Key] = [.serverURL, .deprecatedHttpBaseURL, .serverConfigVersion]

    public static func save(key: Key, value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key.rawValue,
        ]
        SecItemDelete(query as CFDictionary)
        var addQuery = query
        addQuery[kSecValueData as String] = data
        // Accessible after first unlock, not included in backups or device migration
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    public static func load(key: Key) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key.rawValue,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    public static func delete(key: Key) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key.rawValue,
        ]
        SecItemDelete(query as CFDictionary)
    }

    public static func clearAll() {
        for key in [Key.serverURL, .deprecatedHttpBaseURL, .accessToken, .refreshToken,
                    .serverFingerprint, .serverConfigVersion] {
            delete(key: key)
        }
    }
}
