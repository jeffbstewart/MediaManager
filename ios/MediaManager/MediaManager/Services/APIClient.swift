import Foundation

enum APIClientError: LocalizedError {
    case noServerURL
    case invalidURL
    case httpError(Int, String)
    case rateLimited(retryAfter: Int)
    case unauthorized
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .noServerURL: "No server URL configured"
        case .invalidURL: "Invalid URL"
        case .httpError(let code, let message): "HTTP \(code): \(message)"
        case .rateLimited(let seconds): "Rate limited. Retry after \(seconds)s"
        case .unauthorized: "Session expired"
        case .networkError(let error): error.localizedDescription
        }
    }
}

actor APIClient {
    private let session: URLSession
    private var baseURL: URL?
    private var accessToken: String?

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        self.session = URLSession(configuration: config)
    }

    func configure(baseURL: URL, accessToken: String? = nil) {
        self.baseURL = baseURL
        self.accessToken = accessToken
    }

    func setAccessToken(_ token: String?) {
        self.accessToken = token
    }

    func getBaseURL() -> URL? { baseURL }
    func getAccessToken() -> String? { accessToken }

    /// Hit the unauthenticated /discover endpoint over HTTP (LAN) or HTTPS.
    /// Returns API versions and the canonical HTTPS URL for secure communication.
    func discover(serverURL: URL) async throws -> DiscoverResponse {
        let url = serverURL.appendingPathComponent("api/v1/discover")
        let (data, response) = try await session.data(from: url)
        try validateResponse(response, data: data)
        return try JSONDecoder().decode(DiscoverResponse.self, from: data)
    }

    func getServerInfo(serverURL: URL) async throws -> ServerInfo {
        let url = serverURL.appendingPathComponent("api/v1/info")
        var request = URLRequest(url: url)
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try JSONDecoder().decode(ServerInfo.self, from: data)
    }

    func login(username: String, password: String, deviceName: String = "iOS") async throws -> AuthResponse {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = baseURL.appendingPathComponent("api/v1/auth/login")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = ["username": username, "password": password, "device_name": deviceName]
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try JSONDecoder().decode(AuthResponse.self, from: data)
    }

    func refreshToken(_ refreshToken: String) async throws -> AuthResponse {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = baseURL.appendingPathComponent("api/v1/auth/refresh")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = ["refresh_token": refreshToken]
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try JSONDecoder().decode(AuthResponse.self, from: data)
    }

    func revokeToken(_ refreshToken: String) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = baseURL.appendingPathComponent("api/v1/auth/revoke")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let body = ["refresh_token": refreshToken]
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
    }

    func get<T: Decodable>(_ path: String) async throws -> T {
        guard let baseURL else { throw APIClientError.noServerURL }
        // Use string concatenation to preserve query strings — appendingPathComponent
        // percent-encodes '?' which breaks paths like "tmdb/search?q=foo"
        guard let url = URL(string: baseURL.absoluteString + "/api/v1/" + path) else {
            throw APIClientError.invalidURL
        }
        var request = URLRequest(url: url)
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try JSONDecoder().decode(T.self, from: data)
    }

    func post(_ path: String, body: [String: Any]) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = URL(string: baseURL.absoluteString + "/api/v1/" + path)!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
    }

    func put(_ path: String, body: [String: Any] = [:]) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = URL(string: baseURL.absoluteString + "/api/v1/" + path)!
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
    }

    func delete(_ path: String) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = URL(string: baseURL.absoluteString + "/api/v1/" + path)!
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
    }

    /// Build a URL with JWT auth for AVPlayer (uses HTTPAdditionalHeaders, not query params).
    func streamURL(for transcodeId: Int) async -> (URL, [String: String])? {
        guard let baseURL else { return nil }
        let url = baseURL.appendingPathComponent("stream/\(transcodeId)")
        guard let token = accessToken else { return nil }
        return (url, ["Authorization": "Bearer \(token)"])
    }

    /// Hit a non-api path with JWT auth and wait for 200 (e.g. /cam/3/start).
    func warmUpStream(_ path: String) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        guard let url = URL(string: baseURL.absoluteString + path) else {
            throw APIClientError.invalidURL
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 35 // relay warmup can take up to 30s
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
    }

    /// Fetch raw bytes (for images, streams) with JWT auth.
    func getRaw(_ path: String) async throws -> Data {
        guard let baseURL else { throw APIClientError.noServerURL }
        let url = baseURL.appendingPathComponent(path)
        var request = URLRequest(url: url)
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return data
    }

    /// Build the full download URL for a transcode (used by DownloadManager).
    func downloadURL(for transcodeId: Int) -> URL? {
        guard let baseURL else { return nil }
        return URL(string: baseURL.absoluteString + "/api/v1/downloads/\(transcodeId)")
    }

    private func validateResponse(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { return }
        switch http.statusCode {
        case 200..<300:
            return
        case 401:
            throw APIClientError.unauthorized
        case 429:
            let apiError = try? JSONDecoder().decode(APIError.self, from: data)
            throw APIClientError.rateLimited(retryAfter: apiError?.retryAfter ?? 60)
        default:
            let apiError = try? JSONDecoder().decode(APIError.self, from: data)
            throw APIClientError.httpError(http.statusCode, apiError?.error ?? "Unknown error")
        }
    }
}
