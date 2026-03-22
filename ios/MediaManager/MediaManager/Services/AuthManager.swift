import Foundation
import GRPCCore
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
    private(set) var serverUnreachable = false

    let apiClient = APIClient()
    let grpcClient = GrpcClient()
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

        guard let tokenBase64 = KeychainService.load(key: .accessToken),
              let tokenData = Data(base64Encoded: tokenBase64),
              KeychainService.load(key: .refreshToken) != nil else {
            state = .needsLogin(serverURL: url)
            return
        }

        Task {
            // Configure both clients
            let tokenString = String(data: tokenData, encoding: .utf8)
            await apiClient.configure(baseURL: url, accessToken: tokenString)
            try? await grpcClient.configure(host: url.host() ?? "localhost", port: url.port ?? 8080)
            await grpcClient.setAccessToken(tokenData)

            state = .authenticated(serverURL: url)
            await performTokenRefresh()
            await refreshServerInfo()
        }
    }

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
            // Configure gRPC client for discovery (unauthenticated)
            try await grpcClient.configure(host: url.host() ?? "localhost", port: url.port ?? 8080)

            let protoDiscovery = try await grpcClient.discover()
            let discovery = DiscoverResponse(proto: protoDiscovery)

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
                    state = .fingerprintMismatch(
                        serverURL: secureURL,
                        expected: stored,
                        received: receivedFingerprint
                    )
                    return
                }
                KeychainService.save(key: .serverFingerprint, value: receivedFingerprint)
            }

            // Reconfigure gRPC client for the secure URL (may be different host/port)
            try await grpcClient.configure(host: secureURL.host() ?? "localhost", port: secureURL.port ?? 8080)
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
            let response = try await grpcClient.login(
                username: username, password: password, deviceName: deviceName)

            // Tokens are arbitrary bytes — base64 encode for Keychain storage
            let accessBase64 = response.accessToken.base64EncodedString()
            let refreshBase64 = response.refreshToken.base64EncodedString()
            KeychainService.save(key: .accessToken, value: accessBase64)
            KeychainService.save(key: .refreshToken, value: refreshBase64)

            // Set tokens on both clients
            await grpcClient.setAccessToken(response.accessToken)
            let tokenString = String(data: response.accessToken, encoding: .utf8)
            await apiClient.setAccessToken(tokenString)
            updateStreamingCookie(tokenString)

            if response.passwordChangeRequired {
                passwordChangeRequired = true
            }

            if case .needsLogin(let url) = state {
                state = .authenticated(serverURL: url)
            }
            await refreshServerInfo()
            scheduleTokenRefresh(expiresIn: Int(response.expiresIn))
        } catch let rpcError as RPCError {
            switch rpcError.code {
            case .unauthenticated:
                self.error = "Invalid username or password"
            case .permissionDenied:
                self.error = "Account locked. Try again later."
            case .resourceExhausted:
                self.error = "Too many attempts. Please wait."
            default:
                self.error = rpcError.message
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func logout() async {
        refreshTask?.cancel()
        refreshTask = nil

        // Best-effort revoke — always clear local state regardless
        if let refreshBase64 = KeychainService.load(key: .refreshToken),
           let refreshData = Data(base64Encoded: refreshBase64) {
            try? await grpcClient.revoke(token: refreshData)
        }

        KeychainService.delete(key: .accessToken)
        KeychainService.delete(key: .refreshToken)
        await apiClient.setAccessToken(nil)
        await grpcClient.setAccessToken(nil)

        if let urlString = KeychainService.load(key: .serverURL),
           let url = URL(string: urlString) {
            state = .needsLogin(serverURL: url)
        } else {
            state = .needsServer
        }
    }

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
        Task { await grpcClient.close() }
        state = .needsServer
    }

    private func scheduleTokenRefresh(expiresIn: Int = 900) {
        refreshTask?.cancel()
        let delay = max(Double(expiresIn) * 0.8, 30)
        refreshTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            await self?.performTokenRefresh()
        }
    }

    private func performTokenRefresh() async {
        guard let refreshBase64 = KeychainService.load(key: .refreshToken),
              let refreshData = Data(base64Encoded: refreshBase64) else {
            await logout()
            return
        }
        do {
            let response = try await grpcClient.refresh(token: refreshData)

            let accessBase64 = response.accessToken.base64EncodedString()
            let refreshBase64New = response.refreshToken.base64EncodedString()
            KeychainService.save(key: .accessToken, value: accessBase64)
            KeychainService.save(key: .refreshToken, value: refreshBase64New)

            await grpcClient.setAccessToken(response.accessToken)
            let tokenString = String(data: response.accessToken, encoding: .utf8)
            await apiClient.setAccessToken(tokenString)
            updateStreamingCookie(tokenString)

            serverUnreachable = false
            scheduleTokenRefresh(expiresIn: Int(response.expiresIn))
        } catch let rpcError as RPCError where rpcError.code == .unauthenticated {
            await logout()
        } catch {
            serverUnreachable = true
            scheduleTokenRefresh(expiresIn: 30)
        }
    }

    private func updateStreamingCookie(_ token: String?) {
        guard let token,
              case .authenticated(let url) = state,
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

    var cachedCapabilities: [String] {
        UserDefaults.standard.stringArray(forKey: "cachedCapabilities") ?? []
    }

    private func refreshServerInfo() async {
        guard case .authenticated = state else { return }
        do {
            let protoInfo = try await grpcClient.getInfo()
            serverInfo = ServerInfo(proto: protoInfo)
            passwordChangeRequired = protoInfo.user.hasField(fieldNumber: 0) ? false : false
            // Check if user needs password change from profile
            if let caps = serverInfo?.capabilities {
                UserDefaults.standard.set(caps, forKey: "cachedCapabilities")
            }
            serverUnreachable = false
        } catch {
            if serverInfo == nil {
                serverUnreachable = true
            }
        }
    }

    func clearPasswordChangeRequired() {
        passwordChangeRequired = false
    }
}
