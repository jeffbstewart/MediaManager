import Foundation

enum APIClientError: LocalizedError {
    case noServerURL
    case invalidURL
    case httpError(Int, String)
    case unauthorized
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .noServerURL: "No server URL configured"
        case .invalidURL: "Invalid URL"
        case .httpError(let code, let message): "HTTP \(code): \(message)"
        case .unauthorized: "Session expired"
        case .networkError(let error): error.localizedDescription
        }
    }
}

/// HTTP client for binary operations that stay outside gRPC:
/// video streaming (Range requests), image loading, file downloads.
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

    /// Build a URL with JWT auth for AVPlayer (uses HTTPAdditionalHeaders, not query params).
    func streamURL(for transcodeId: Int) async -> (URL, [String: String])? {
        guard let baseURL else { return nil }
        let url = baseURL.appendingPathComponent("stream/\(transcodeId)")
        guard let token = accessToken else { return nil }
        return (url, ["Authorization": "Bearer \(token)"])
    }

    /// Hit a path with JWT auth and wait for 200 (e.g. HLS relay warmup).
    func warmUpStream(_ path: String) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        guard let url = URL(string: baseURL.absoluteString + path) else {
            throw APIClientError.invalidURL
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 35
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

    /// Generic GET with JWT auth and JSON decoding (used by DownloadManager for manifests).
    func get<T: Decodable>(_ path: String) async throws -> T {
        guard let baseURL else { throw APIClientError.noServerURL }
        guard let url = URL(string: baseURL.absoluteString + "/" + path) else {
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

    /// POST with JWT auth (used by DownloadManager for progress sync).
    func post(_ path: String, body: [String: Any]) async throws {
        guard let baseURL else { throw APIClientError.noServerURL }
        guard let url = URL(string: baseURL.absoluteString + "/" + path) else {
            throw APIClientError.invalidURL
        }
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

    /// Build the full download URL for a transcode (used by DownloadManager).
    func downloadURL(for transcodeId: Int) -> URL? {
        guard let baseURL else { return nil }
        return URL(string: baseURL.absoluteString + "/downloads/\(transcodeId)")
    }

    private func validateResponse(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { return }
        switch http.statusCode {
        case 200..<300:
            return
        case 401:
            throw APIClientError.unauthorized
        default:
            throw APIClientError.httpError(http.statusCode, "HTTP error")
        }
    }
}
