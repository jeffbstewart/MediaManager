import Foundation

struct DiscoverResponse: Codable {
    let apiVersions: [String]
    let authMethods: [String]
    let secureUrl: String?
    let serverFingerprint: String?

    enum CodingKeys: String, CodingKey {
        case apiVersions = "api_versions"
        case authMethods = "auth_methods"
        case secureUrl = "secure_url"
        case serverFingerprint = "server_fingerprint"
    }
}

struct ServerInfo: Codable {
    let serverVersion: String
    let apiVersion: String
    let capabilities: [String]
    let titleCount: Int
    let user: ServerUserInfo?

    enum CodingKeys: String, CodingKey {
        case serverVersion = "server_version"
        case apiVersion = "api_version"
        case capabilities
        case titleCount = "title_count"
        case user
    }
}

struct ServerUserInfo: Codable {
    let id: UserID
    let username: String
    let displayName: String
    let isAdmin: Bool
    let ratingCeiling: Int?
    let ratingCeilingLabel: String?
    let fulfilledWishCount: Int?
    let passwordChangeRequired: Bool?

    enum CodingKeys: String, CodingKey {
        case id, username
        case displayName = "display_name"
        case isAdmin = "is_admin"
        case ratingCeiling = "rating_ceiling"
        case ratingCeilingLabel = "rating_ceiling_label"
        case fulfilledWishCount = "fulfilled_wish_count"
        case passwordChangeRequired = "password_change_required"
    }
}

struct AuthResponse: Codable {
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

struct APIError: Codable {
    let error: String
    let retryAfter: Int?

    enum CodingKeys: String, CodingKey {
        case error
        case retryAfter = "retry_after"
    }
}
