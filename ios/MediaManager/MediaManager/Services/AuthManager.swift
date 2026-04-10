import Foundation
import GRPCCore
import LocalAuthentication
import Observation
import UIKit
import os.log

private let logger = Logger(subsystem: "net.stewart.mediamanager", category: "AuthManager")

@Observable
@MainActor
final class AuthManager {
    enum State: Equatable {
        case needsServer
        case needsSetup(serverURL: URL)
        case needsLogin(serverURL: URL)
        case needsLegalAgreement(serverURL: URL)
        case authenticated(serverURL: URL)
        case fingerprintMismatch(serverURL: URL, expected: String, received: String)
    }

    private(set) var state: State = .needsServer
    private(set) var serverInfo: ServerInfo?
    private(set) var error: String?
    private(set) var passwordChangeRequired = false
    private(set) var serverUnreachable = false
    private(set) var legalDocs: MMLegalDocumentInfo?
    private(set) var legalStatus: MMLegalStatusResponse?
    /// True when tokens exist but biometric gate hasn't been passed yet.
    private(set) var awaitingBiometric = false

    let apiClient = APIClient()
    let grpcClient = GrpcClient()
    private var refreshTask: Task<Void, Never>?

    private static let legalDocsKey = "cachedLegalDocs"
    private static let biometricEnabledKey = "biometricLoginEnabled"

    var biometricLoginEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: Self.biometricEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.biometricEnabledKey) }
    }

    /// True if the device has biometric hardware (Face ID, Touch ID, Optic ID).
    /// Does not require enrollment — the toggle is shown so the user knows the
    /// option exists, and enrollment errors are handled at authentication time.
    static var isBiometricAvailable: Bool {
        let context = LAContext()
        // Check canEvaluatePolicy to populate biometryType, ignore the result
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType != .none
    }

    static var biometricTypeName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        @unknown default: return "Biometrics"
        }
    }

    init() {
        restoreLegalDocs()
        restoreSession()
    }

    private func restoreSession() {
        logger.info("restoreSession: checking keychain")
        guard let urlString = KeychainService.load(key: .serverURL),
              let url = URL(string: urlString) else {
            logger.info("restoreSession: no server URL, needsServer")
            state = .needsServer
            return
        }
        logger.info("restoreSession: server URL = \(urlString)")

        // Configure gRPC client from the stored server URL (what the user entered)
        let grpcHost = url.host() ?? "localhost"
        let grpcPort = url.port ?? (url.scheme == "https" ? 443 : 8080)
        // Configure HTTP client from the stored HTTP base URL (from Discover's secure_url)
        let httpURLString = KeychainService.load(key: .httpBaseURL) ?? urlString
        let httpURL = URL(string: httpURLString) ?? url
        Task {
            logger.info("restoreSession: gRPC=\(grpcHost):\(grpcPort), HTTP=\(httpURLString)")
            await apiClient.configure(baseURL: httpURL)
            try? await grpcClient.configure(host: grpcHost, port: grpcPort, useTLS: url.scheme == "https")
            // Refresh legal docs from server if not cached locally
            if legalDocs == nil {
                await refreshLegalDocs()
            }
        }

        guard let tokenBase64 = KeychainService.load(key: .accessToken),
              let tokenData = Data(base64Encoded: tokenBase64),
              KeychainService.load(key: .refreshToken) != nil else {
            logger.info("restoreSession: no tokens, needsLogin")
            state = .needsLogin(serverURL: url)
            return
        }
        logger.info("restoreSession: found tokens, setting access token")

        if biometricLoginEnabled && Self.isBiometricAvailable {
            // Tokens exist but require biometric verification before use
            logger.info("restoreSession: biometric gate — waiting for user")
            awaitingBiometric = true
            state = .needsLogin(serverURL: url)
            return
        }

        Task {
            let tokenString = String(data: tokenData, encoding: .utf8)
            await apiClient.setAccessToken(tokenString)
            await grpcClient.setAccessToken(tokenData)

            await performTokenRefresh()
            await transitionAfterAuth(serverURL: url)
        }
    }

    func connectToServer(urlString: String) async {
        logger.info("connectToServer: '\(urlString)'")
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
            let discHost = url.host() ?? "localhost"
            let discPort = url.port ?? (url.scheme == "https" ? 443 : 8080)
            logger.info("connectToServer: configuring gRPC for discovery at \(discHost):\(discPort)")
            try await grpcClient.configure(host: discHost, port: discPort, useTLS: url.scheme == "https")

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

            // gRPC client stays on the URL the user entered — don't reconfigure it.
            // APIClient uses the secure_url from Discover for HTTP operations (images, streaming).
            KeychainService.save(key: .serverURL, value: normalized)
            KeychainService.save(key: .httpBaseURL, value: secureURLString)
            await apiClient.configure(baseURL: secureURL)
            // Store legal document info from server
            if protoDiscovery.hasLegal {
                legalDocs = protoDiscovery.legal
                persistLegalDocs(legalDocs)
            }
            logger.info("connectToServer: success — gRPC=\(normalized), HTTP=\(secureURLString), setupRequired=\(protoDiscovery.setupRequired)")
            if protoDiscovery.setupRequired {
                state = .needsSetup(serverURL: url)
            } else {
                state = .needsLogin(serverURL: url)
            }
        } catch {
            self.error = "Cannot reach server: \(error.localizedDescription)"
        }
    }

    func createFirstUser(username: String, password: String, displayName: String) async {
        logger.info("createFirstUser: creating admin account")
        error = nil
        let deviceName = UIDevice.current.name
        do {
            let response = try await grpcClient.createFirstUser(
                username: username, password: password,
                displayName: displayName, deviceName: deviceName)

            let accessBase64 = response.accessToken.base64EncodedString()
            let refreshBase64 = response.refreshToken.base64EncodedString()
            KeychainService.save(key: .accessToken, value: accessBase64)
            KeychainService.save(key: .refreshToken, value: refreshBase64)

            await grpcClient.setAccessToken(response.accessToken)
            let tokenString = String(data: response.accessToken, encoding: .utf8)
            await apiClient.setAccessToken(tokenString)
            updateStreamingCookie(tokenString)

            if case .needsSetup(let url) = state {
                state = .authenticated(serverURL: url)
            }
            scheduleTokenRefresh(expiresIn: Int(response.expiresIn))
            await refreshServerInfo()
        } catch let rpcError as RPCError {
            switch rpcError.code {
            case .alreadyExists:
                self.error = "Setup already complete — an account already exists"
                // Transition to login since users exist
                if case .needsSetup(let url) = state {
                    state = .needsLogin(serverURL: url)
                }
            case .invalidArgument:
                self.error = rpcError.message
            default:
                self.error = rpcError.message
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func login(username: String, password: String) async {
        logger.info("login: starting for user (redacted)")
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

            scheduleTokenRefresh(expiresIn: Int(response.expiresIn))

            // Check legal compliance before transitioning to authenticated
            if case .needsLogin(let url) = state {
                await transitionAfterAuth(serverURL: url)
            }

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
        legalDocs = nil
        legalStatus = nil
        awaitingBiometric = false
        biometricLoginEnabled = false
        persistLegalDocs(nil)
        Task { await grpcClient.close() }
        state = .needsServer
    }

    /// After successful login or session restore, check legal compliance
    /// and transition to either needsLegalAgreement or authenticated.
    private func transitionAfterAuth(serverURL: URL) async {
        do {
            let status = try await grpcClient.getLegalStatus()
            legalStatus = status
            if status.compliant {
                state = .authenticated(serverURL: serverURL)
                await refreshServerInfo()
            } else {
                logger.info("transitionAfterAuth: legal agreement required")
                state = .needsLegalAgreement(serverURL: serverURL)
            }
        } catch {
            // If we can't check legal status, proceed to authenticated
            // (the server's AuthInterceptor will enforce compliance on gated RPCs)
            logger.warning("transitionAfterAuth: legal check failed, proceeding: \(error.localizedDescription)")
            state = .authenticated(serverURL: serverURL)
            await refreshServerInfo()
        }
    }

    func agreeToTerms() async {
        guard let status = legalStatus else { return }
        error = nil
        do {
            _ = try await grpcClient.agreeToTerms(
                privacyPolicyVersion: status.requiredPrivacyPolicyVersion,
                termsOfUseVersion: status.requiredTermsOfUseVersion)
            legalStatus = nil
            if case .needsLegalAgreement(let url) = state {
                state = .authenticated(serverURL: url)
                await refreshServerInfo()
            }
        } catch let rpcError as RPCError {
            self.error = rpcError.message
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Biometric Login

    /// Authenticate with Face ID / Touch ID to unlock stored session tokens.
    func authenticateWithBiometric() async {
        let context = LAContext()
        let reason = "Sign in to Media Manager"
        do {
            let success = try await context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
            guard success else { return }
        } catch {
            logger.warning("biometric auth failed: \(error.localizedDescription)")
            // User cancelled or failed — stay on login screen
            return
        }

        // Biometric passed — restore session from stored tokens
        awaitingBiometric = false
        guard let tokenBase64 = KeychainService.load(key: .accessToken),
              let tokenData = Data(base64Encoded: tokenBase64) else {
            return
        }

        let tokenString = String(data: tokenData, encoding: .utf8)
        await apiClient.setAccessToken(tokenString)
        await grpcClient.setAccessToken(tokenData)

        guard case .needsLogin(let url) = state else { return }
        await performTokenRefresh()
        await transitionAfterAuth(serverURL: url)
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
        guard let token else { return }
        // Extract the server URL from whichever authenticated-ish state we're in
        let url: URL
        switch state {
        case .authenticated(let u), .needsLegalAgreement(let u), .needsLogin(let u):
            url = u
        default:
            return
        }
        guard let host = url.host() else { return }
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
            // password_change_required is set during login (from TokenResponse).
            // The server blocks gated RPCs with PERMISSION_DENIED if must_change_password is set.
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

    private func refreshLegalDocs() async {
        do {
            let protoDiscovery = try await grpcClient.discover()
            if protoDiscovery.hasLegal {
                legalDocs = protoDiscovery.legal
                persistLegalDocs(legalDocs)
                logger.info("restoreSession: refreshed legal docs from server")
            }
        } catch {
            logger.warning("restoreSession: failed to refresh legal docs: \(error.localizedDescription)")
        }
    }

    private func restoreLegalDocs() {
        guard let data = UserDefaults.standard.data(forKey: Self.legalDocsKey) else { return }
        legalDocs = try? MMLegalDocumentInfo(serializedData: data)
    }

    private func persistLegalDocs(_ docs: MMLegalDocumentInfo?) {
        if let docs, let data = try? docs.serializedData() {
            UserDefaults.standard.set(data, forKey: Self.legalDocsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.legalDocsKey)
        }
    }
}
