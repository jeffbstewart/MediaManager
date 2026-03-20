import Foundation

struct ServerInfo: Codable {
    let version: String
    let apiVersion: Int
    let capabilities: [String]
    let titleCount: Int

    enum CodingKeys: String, CodingKey {
        case version
        case apiVersion = "api_version"
        case capabilities
        case titleCount = "title_count"
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
