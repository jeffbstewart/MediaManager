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
    }

    private(set) var state: State = .needsServer
    private(set) var serverInfo: ServerInfo?
    private(set) var error: String?

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
            await refreshServerInfo()
            scheduleTokenRefresh()
        }
    }

    func connectToServer(urlString: String) async {
        error = nil
        var normalized = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        if !normalized.hasPrefix("http://") && !normalized.hasPrefix("https://") {
            normalized = "https://\(normalized)"
        }
        // Strip trailing slashes
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        guard let url = URL(string: normalized) else {
            error = "Invalid URL"
            return
        }
        do {
            let info = try await apiClient.getServerInfo(serverURL: url)
            serverInfo = info
            KeychainService.save(key: .serverURL, value: normalized)
            await apiClient.configure(baseURL: url)
            state = .needsLogin(serverURL: url)
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
            scheduleTokenRefresh(expiresIn: response.expiresIn)
        } catch is APIClientError {
            await logout()
        } catch {
            // Network error — retry in 30 seconds
            scheduleTokenRefresh(expiresIn: 30)
        }
    }

    private func refreshServerInfo() async {
        guard case .authenticated(let url) = state else { return }
        serverInfo = try? await apiClient.getServerInfo(serverURL: url)
    }
}
