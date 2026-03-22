import Foundation
import Observation
import UIKit

@Observable
@MainActor
final class AuthManager {
    enum State: Equatable {
        case needsServer
        case needsLogin(serverURL: URL)
        case authenticated(serverURL: URL)
        case fingerprintMismatch(serverURL: URL, expected: String, received: String)
    }

    private(set) var state: State = .needsServer
    private(set) var serverInfo: ServerInfo?
    private(set) var error: String?
    private(set) var passwordChangeRequired = false
    /// Set when authenticated but server is unreachable (network error during token refresh/info fetch).
    private(set) var serverUnreachable = false

    let apiClient = APIClient()
    private var refreshTask: Task<Void, Never>?

    init() {
        restoreSession()
    }

    private func restoreSession() {
        guard let urlString = KeychainService.load(key: .serverURL),
              let url = URL(string: urlString) else {
            state = .needsServer
            return
        }

        guard let accessToken = KeychainService.load(key: .accessToken),
              KeychainService.load(key: .refreshToken) != nil else {
            state = .needsLogin(serverURL: url)
            return
        }

        Task {
            await apiClient.configure(baseURL: url, accessToken: accessToken)
            state = .authenticated(serverURL: url)
            // The stored access token may be expired — refresh immediately
            await performTokenRefresh()
            await refreshServerInfo()
        }
    }

    /// Connect via a URL entered manually (assumed HTTPS) or discovered via SSDP (HTTP LAN).
    /// If the URL is HTTP, hits /discover to get the canonical HTTPS URL.
    /// If the URL is already HTTPS, validates via /discover directly.
    func connectToServer(urlString: String) async {
        error = nil
        var normalized = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        if !normalized.hasPrefix("http://") && !normalized.hasPrefix("https://") {
            normalized = "https://\(normalized)"
        }
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        guard let url = URL(string: normalized) else {
            error = "Invalid URL"
            return
        }
        do {
            let discovery = try await apiClient.discover(serverURL: url)

            // Use the secure_url from the server if available, otherwise use what was entered
            let secureURLString: String
            if let secureUrl = discovery.secureUrl, !secureUrl.isEmpty {
                guard secureUrl.hasPrefix("https://") else {
                    self.error = "Server returned insecure URL — connection refused"
                    return
                }
                secureURLString = secureUrl
            } else if normalized.hasPrefix("https://") {
                secureURLString = normalized
            } else {
                self.error = "Server did not provide a secure URL"
                return
            }

            guard let secureURL = URL(string: secureURLString) else {
                self.error = "Server returned invalid secure URL"
                return
            }

            // TOFU fingerprint verification
            if let receivedFingerprint = discovery.serverFingerprint {
                let storedFingerprint = KeychainService.load(key: .serverFingerprint)
                if let stored = storedFingerprint, stored != receivedFingerprint {
                    // Fingerprint changed — possible MITM or key rotation
                    state = .fingerprintMismatch(
                        serverURL: secureURL,
                        expected: stored,
                        received: receivedFingerprint
                    )
                    return
                }
                // First connection or fingerprint matches — store/update it
                KeychainService.save(key: .serverFingerprint, value: receivedFingerprint)
            }

            KeychainService.save(key: .serverURL, value: secureURLString)
            await apiClient.configure(baseURL: secureURL)
            state = .needsLogin(serverURL: secureURL)
        } catch {
            self.error = "Cannot reach server: \(error.localizedDescription)"
        }
    }

    func login(username: String, password: String) async {
        error = nil
        let deviceName = UIDevice.current.name
        do {
            let response = try await apiClient.login(
                username: username, password: password, deviceName: deviceName)
            KeychainService.save(key: .accessToken, value: response.accessToken)
            KeychainService.save(key: .refreshToken, value: response.refreshToken)
            await apiClient.setAccessToken(response.accessToken)

            if case .needsLogin(let url) = state {
                state = .authenticated(serverURL: url)
            }
            await refreshServerInfo()
            scheduleTokenRefresh(expiresIn: response.expiresIn)
        } catch let apiError as APIClientError {
            switch apiError {
            case .httpError(_, let message) where message == "invalid_credentials":
                self.error = "Invalid username or password"
            case .httpError(_, let message) where message == "account_locked":
                self.error = "Account locked. Try again later."
            case .rateLimited(let seconds):
                self.error = "Too many attempts. Wait \(seconds) seconds."
            default:
                self.error = apiError.localizedDescription
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func logout() async {
        refreshTask?.cancel()
        refreshTask = nil
        if let refreshToken = KeychainService.load(key: .refreshToken) {
            try? await apiClient.revokeToken(refreshToken)
        }
        KeychainService.delete(key: .accessToken)
        KeychainService.delete(key: .refreshToken)
        await apiClient.setAccessToken(nil)

        if let urlString = KeychainService.load(key: .serverURL),
           let url = URL(string: urlString) {
            state = .needsLogin(serverURL: url)
        } else {
            state = .needsServer
        }
    }

    /// Accept a changed server fingerprint (after deliberate key rotation).
    /// Clears the stored fingerprint and retries connection.
    func acceptFingerprintChange() async {
        KeychainService.delete(key: .serverFingerprint)
        if case .fingerprintMismatch(let url, _, _) = state {
            await connectToServer(urlString: url.absoluteString)
        }
    }

    func disconnectServer() {
        refreshTask?.cancel()
        refreshTask = nil
        KeychainService.clearAll()
        serverInfo = nil
        state = .needsServer
    }

    private func scheduleTokenRefresh(expiresIn: Int = 900) {
        refreshTask?.cancel()
        // Refresh at 80% of the token lifetime
        let delay = max(Double(expiresIn) * 0.8, 30)
        refreshTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            await self?.performTokenRefresh()
        }
    }

    private func performTokenRefresh() async {
        guard let refreshToken = KeychainService.load(key: .refreshToken) else {
            await logout()
            return
        }
        do {
            let response = try await apiClient.refreshToken(refreshToken)
            KeychainService.save(key: .accessToken, value: response.accessToken)
            KeychainService.save(key: .refreshToken, value: response.refreshToken)
            await apiClient.setAccessToken(response.accessToken)
            updateStreamingCookie(response.accessToken)
            serverUnreachable = false
            scheduleTokenRefresh(expiresIn: response.expiresIn)
        } catch APIClientError.unauthorized {
            // Refresh token itself was rejected — session is truly dead
            await logout()
        } catch APIClientError.httpError(let code, _) where code == 401 {
            await logout()
        } catch {
            // Network error or server error — mark unreachable and retry
            serverUnreachable = true
            scheduleTokenRefresh(expiresIn: 30)
        }
    }

    /// Updates the mm_jwt cookie used by AVPlayer for HLS streaming.
    /// Called on every token refresh so active streams don't lose auth.
    private func updateStreamingCookie(_ token: String) {
        guard case .authenticated(let url) = state,
              let host = url.host() else { return }
        var cookieProps: [HTTPCookiePropertyKey: Any] = [
            .name: "mm_jwt",
            .value: token,
            .domain: host,
            .path: "/",
        ]
        if url.scheme == "https" {
            cookieProps[.secure] = "TRUE"
        }
        let cookie = HTTPCookie(properties: cookieProps)
        if let cookie {
            HTTPCookieStorage.shared.setCookie(cookie)
        }
    }

    /// Cached capabilities from the last successful server info fetch.
    /// Used to show/hide features (like Downloads tab) when the server is unreachable.
    var cachedCapabilities: [String] {
        UserDefaults.standard.stringArray(forKey: "cachedCapabilities") ?? []
    }

    private func refreshServerInfo() async {
        guard case .authenticated(let url) = state else { return }
        do {
            serverInfo = try await apiClient.getServerInfo(serverURL: url)
            passwordChangeRequired = serverInfo?.user?.passwordChangeRequired ?? false
            if let caps = serverInfo?.capabilities {
                UserDefaults.standard.set(caps, forKey: "cachedCapabilities")
            }
            serverUnreachable = false
        } catch {
            // Server info fetch failed — server may be unreachable
            if serverInfo == nil {
                serverUnreachable = true
            }
        }
    }

    func clearPasswordChangeRequired() {
        passwordChangeRequired = false
    }
}
