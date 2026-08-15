import Foundation
import GRPCCore
import LocalAuthentication
import Observation
import UIKit
import os.log
import MediaManagerProtos

private let logger = MMLogger(category: "AuthManager")

@Observable
@MainActor
public final class AuthManager {
    public enum State: Equatable {
        /// Initial state on every launch. Held until restoreSession's
        /// async Task either completes auth or commits to one of the
        /// other states. Without it, RootView painted ServerSetupView
        /// for the brief gap between init() returning and the Task
        /// running, even when the keychain already had server URL +
        /// valid tokens — looked like the app forgot the user.
        case restoring
        case needsServer
        case needsSetup(serverURL: URL)
        case needsLogin(serverURL: URL)
        case needsLegalAgreement(serverURL: URL)
        case authenticated(serverURL: URL)
        case fingerprintMismatch(serverURL: URL, expected: String, received: String)
    }

    public private(set) var state: State = .restoring
    public private(set) var serverInfo: ServerInfo?
    public private(set) var error: String?
    public private(set) var passwordChangeRequired = false
    public private(set) var serverUnreachable = false
    public private(set) var legalDocs: MMLegalDocumentInfo?
    public private(set) var legalStatus: MMLegalStatusResponse?
    /// True when tokens exist but biometric gate hasn't been passed yet.
    public private(set) var awaitingBiometric = false

    public let apiClient = APIClient()
    public let grpcClient = GrpcClient()
    /// Ships os.Logger output to Binnacle via ObservabilityService.StreamLogs.
    /// Started once an authenticated grpcClient is available; stopped on logout.
    /// One streamer per AuthManager instance so the underlying LogBuffer iterator
    /// stays single-consumer for the lifetime of the app.
    public let logStreamer: LogStreamer
    private var refreshTask: Task<Void, Never>?

    private static let legalDocsKey = "cachedLegalDocs"
    private static let biometricEnabledKey = "biometricLoginEnabled"

    /// Version of the persisted server endpoint, stamped into the
    /// Keychain alongside the URL. Bump it when a server address
    /// migration has to invalidate every endpoint stored by older
    /// builds: `restoreSession()` discards any endpoint stamped below
    /// this version and starts at server setup, as though no server
    /// had ever been configured.
    ///
    /// v2 (2026-08): the household hosting endpoint moved, so every v1
    /// endpoint points at a dead address. This matters more on iOS
    /// than on the other clients — the endpoint lives in the Keychain,
    /// which survives deleting and reinstalling the app, so even a
    /// reinstall came back up pointed at the dead address.
    public static let serverConfigVersion = 2

    /// True when a full session (both tokens) is on hand to resume.
    private var hasStoredTokens: Bool {
        KeychainService.load(key: .accessToken) != nil
            && KeychainService.load(key: .refreshToken) != nil
    }

    public var biometricLoginEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: Self.biometricEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.biometricEnabledKey) }
    }

    /// True if the device has biometric hardware (Face ID, Touch ID, Optic ID).
    /// Does not require enrollment — the toggle is shown so the user knows the
    /// option exists, and enrollment errors are handled at authentication time.
    public static var isBiometricAvailable: Bool {
        let context = LAContext()
        // Check canEvaluatePolicy to populate biometryType, ignore the result
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType != .none
    }

    public static var biometricTypeName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        @unknown default: return "Biometrics"
        }
    }

    public init() {
        self.logStreamer = LogStreamer(grpcClient: grpcClient, identity: .current())
        purgeKeychainIfFreshInstall()
        restoreLegalDocs()
        restoreSession()
        // Let LogStreamer ask us to rotate the access token when the
        // server rejects its reconnect attempts with UNAUTHENTICATED.
        // Long-suspended apps' tokens can expire past their schedule
        // refresh; without this the log stream stays dead even though
        // a refresh would heal it.
        Task { [weak self] in
            await self?.logStreamer.setAuthFailedHandler { [weak self] in
                await self?.performTokenRefresh()
            }
        }
    }

    /// Deleting an iOS app wipes its container — UserDefaults, caches,
    /// documents — but *not* its Keychain items, so reinstalling used
    /// to come back up still pointed at the old server with the old
    /// tokens. That surprises users ("I uninstalled it and it still
    /// remembers my server") and, when the stored address is dead,
    /// makes reinstalling useless as a recovery move.
    ///
    /// UserDefaults is wiped on uninstall, so the absence of this flag
    /// means "container is brand new" — either a first install or a
    /// reinstall. Either way there is nothing legitimate to carry over,
    /// so clear the Keychain and start clean.
    private func purgeKeychainIfFreshInstall() {
        let defaults = UserDefaults.standard
        let seededKey = "keychainSeededForInstall"
        guard !defaults.bool(forKey: seededKey) else { return }
        defaults.set(true, forKey: seededKey)

        // Builds before this flag existed never wrote it, so on the
        // first launch after *updating* to this build its absence looks
        // exactly like a reinstall — and purging there would sign
        // everyone out for no reason. Any of these keys means the
        // container carried over from a previous run, so this is an
        // update: seed the flag and leave the Keychain alone. From the
        // next launch on, the flag itself is the signal.
        let containerCarriedOver = [Self.legalDocsKey, "cachedCapabilities",
                                    "offlineMode", Self.biometricEnabledKey]
            .contains { defaults.object(forKey: $0) != nil }
        guard !containerCarriedOver else {
            logger.info("purgeKeychain: no marker but container has prior state — treating as an update")
            return
        }

        guard KeychainService.load(key: .serverURL) != nil
                || KeychainService.load(key: .refreshToken) != nil else { return }
        logger.info("purgeKeychain: fresh container with Keychain leftovers from a previous install — clearing")
        KeychainService.clearAll()
    }

    private func restoreSession() {
        logger.info("restoreSession: checking keychain")
        guard let urlString = KeychainService.load(key: .serverURL),
              let url = URL(string: urlString) else {
            logger.info("restoreSession: no server URL, needsServer")
            state = .needsServer
            return
        }
        let storedConfigVersion = Int(KeychainService.load(key: .serverConfigVersion) ?? "") ?? 1
        guard storedConfigVersion >= Self.serverConfigVersion else {
            // Stored before the current endpoint migration, so it points
            // at an address that no longer answers. Drop it and re-run
            // server setup; the tokens survive and resume once the new
            // address is entered.
            logger.info("restoreSession: stored endpoint is config v\(storedConfigVersion), need v\(Self.serverConfigVersion) — discarding")
            clearServerEndpoint()
            state = .needsServer
            return
        }
        logger.info("restoreSession: server URL = \(urlString)")

        Task {
            await configureClients(for: url)
            // Always re-run Discover. The legal-docs path is its
            // historical reason to exist (only fires on first launch
            // with no cache), but the same call now also refreshes
            // the endpoint — which has to run every launch so a moved
            // server doesn't strand us pointing at a stale hostname
            // forever. The legal-docs half is a no-op when already
            // cached.
            // Skipped when offline mode is on — the RPC would be
            // gated anyway, and the cached legal docs / endpoint are
            // good enough for an offline session.
            if !UserDefaults.standard.bool(forKey: "offlineMode") {
                await refreshLegalDocs()
            }
        }

        guard hasStoredTokens else {
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

        Task { await resumeStoredSession(serverURL: url) }
    }

    /// Adopt the tokens already in the Keychain for `serverURL` and
    /// settle into the state they earn. Shared by launch-time restore,
    /// the biometric unlock, and server setup after a re-point —
    /// tokens are issued by the server and aren't bound to its
    /// address, so pointing at the same server's new address resumes
    /// the session instead of demanding credentials again.
    private func resumeStoredSession(serverURL url: URL) async {
        guard let tokenBase64 = KeychainService.load(key: .accessToken),
              let tokenData = Data(base64Encoded: tokenBase64) else {
            state = .needsLogin(serverURL: url)
            return
        }
        let tokenString = String(data: tokenData, encoding: .utf8)
        await apiClient.setAccessToken(tokenString)
        await grpcClient.setAccessToken(tokenData)

        // Offline-mode fast path: don't sit on the "Connecting…"
        // spinner while a doomed network round-trip times out.
        // The user has explicitly opted into offline browsing; we
        // already have stored tokens; ContentView's offline UI
        // handles cached-only rendering. Schedule a periodic
        // refresh anyway — first attempts will fail without
        // network, but they'll keep rescheduling so tokens
        // refresh automatically once connectivity returns.
        if UserDefaults.standard.bool(forKey: "offlineMode") {
            logger.info("resumeStoredSession: offline mode — skipping token refresh, going straight to authenticated")
            state = .authenticated(serverURL: url)
            scheduleTokenRefresh()
            return
        }

        await performTokenRefresh()
        // performTokenRefresh may have called logout() when the refresh
        // token was rejected (typical after a long absence). Honour
        // that — don't force-transition back to authenticated and
        // strand the user on empty screens with no valid token.
        // Use the keychain as the source of truth: logout clears it,
        // a successful refresh writes a fresh access token.
        guard KeychainService.load(key: .accessToken) != nil else { return }
        await transitionAfterAuth(serverURL: url)
    }

    /// Point both clients at `url`. The server exposes gRPC, HTTP, and
    /// streaming on a single Armeria port, so one host + one port
    /// describes the whole endpoint — no separate gRPC/HTTP addresses
    /// to keep in sync (that split was a servlet-era artifact).
    private func configureClients(for url: URL) async {
        let host = url.host() ?? "localhost"
        let port = url.port ?? Self.defaultPort(forScheme: url.scheme)
        logger.info("configureClients: \(url.scheme ?? "https")://\(host):\(port)")
        await apiClient.configure(baseURL: url)
        try? await grpcClient.configure(host: host, port: port, useTLS: url.scheme == "https")
    }

    /// Port to use when the URL doesn't name one. HTTPS goes through
    /// the reverse proxy on 443; plaintext talks to Armeria directly,
    /// which serves gRPC + HTTP + the SPA on 9090.
    private static func defaultPort(forScheme scheme: String?) -> Int {
        scheme == "https" ? 443 : 9090
    }

    /// Persist `url` as *the* server endpoint, stamped with the
    /// current config version so the launch-time gate accepts it.
    private func storeServerEndpoint(_ url: URL) {
        KeychainService.save(key: .serverURL, value: url.absoluteString)
        KeychainService.save(key: .serverConfigVersion, value: String(Self.serverConfigVersion))
    }

    /// Drop the stored address (and the legacy split-endpoint row that
    /// pre-v2 builds wrote) while leaving the session tokens alone.
    private func clearServerEndpoint() {
        for key in KeychainService.endpointKeys {
            KeychainService.delete(key: key)
        }
    }

    public func connectToServer(urlString: String) async {
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
            let discPort = url.port ?? Self.defaultPort(forScheme: url.scheme)
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

            // The secure URL Discover reports is the server's canonical
            // public address, and one address now covers everything —
            // gRPC, images, streaming — so adopt it wholesale rather
            // than keeping the typed-in address for gRPC and the
            // discovered one for HTTP. That's what lets an address
            // migration heal itself: whatever the user typed to reach
            // the server, they end up stored on the canonical name.
            storeServerEndpoint(secureURL)
            await configureClients(for: secureURL)
            // Store legal document info from server
            if protoDiscovery.hasLegal {
                legalDocs = protoDiscovery.legal
                persistLegalDocs(legalDocs)
            }
            logger.info("connectToServer: success — endpoint=\(secureURLString), setupRequired=\(protoDiscovery.setupRequired)")
            if protoDiscovery.setupRequired {
                state = .needsSetup(serverURL: secureURL)
            } else if hasStoredTokens {
                // Accounts survive a server change (changeServer keeps
                // the tokens), so route by what's actually stored
                // instead of forcing a re-login after a re-point.
                state = .needsLogin(serverURL: secureURL)
                if biometricLoginEnabled && Self.isBiometricAvailable {
                    awaitingBiometric = true
                } else {
                    await resumeStoredSession(serverURL: secureURL)
                }
            } else {
                state = .needsLogin(serverURL: secureURL)
            }
        } catch {
            logger.warning("connectToServer: \(String(describing: error))")
            self.error = "Cannot reach server. \(Self.describe(error))"
        }
    }

    /// Human-readable failure text. `RPCError` has no useful
    /// `localizedDescription` — it renders as "GRPCCore.RPCError error
    /// 1", which tells a user nothing — so pull the status code and
    /// server message out by hand and say what to do about it.
    private static func describe(_ error: Error) -> String {
        guard let rpc = error as? RPCError else {
            return error.localizedDescription
        }
        let detail = rpc.message.isEmpty ? "" : " (\(rpc.message))"
        switch rpc.code {
        case .unavailable:
            return "Nothing answered at that address. Check the address, "
                + "and that this device is on a network that can reach it."
                + detail
        case .deadlineExceeded:
            return "The server took too long to answer.\(detail)"
        case .unimplemented:
            return "That address answered, but it isn't a Media Manager server.\(detail)"
        default:
            return "\(rpc.code)\(detail)"
        }
    }

    public func createFirstUser(username: String, password: String, displayName: String) async {
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
            await logStreamer.start()
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

    public func login(username: String, password: String) async {
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

    /// Sign out. Local state is dropped unconditionally and without
    /// waiting on the network: signing out is exactly what a user
    /// reaches for when the server has gone dark, and awaiting a
    /// revoke against an unreachable host used to hang the button for
    /// as long as the transport's connect timeout. The revoke is fired
    /// off to the side as a courtesy — if it never lands, the server
    /// expires the refresh token on its own schedule.
    public func logout() async {
        refreshTask?.cancel()
        refreshTask = nil
        await logStreamer.stop()

        if let refreshBase64 = KeychainService.load(key: .refreshToken),
           let refreshData = Data(base64Encoded: refreshBase64) {
            let client = grpcClient
            Task.detached {
                let revoke = Task { try? await client.revoke(token: refreshData) }
                try? await Task.sleep(for: .seconds(10))
                revoke.cancel()
            }
        }

        KeychainService.delete(key: .accessToken)
        KeychainService.delete(key: .refreshToken)
        await apiClient.setAccessToken(nil)
        await grpcClient.setAccessToken(nil)
        awaitingBiometric = false
        serverInfo = nil
        legalStatus = nil
        serverUnreachable = false

        if let urlString = KeychainService.load(key: .serverURL),
           let url = URL(string: urlString) {
            state = .needsLogin(serverURL: url)
        } else {
            state = .needsServer
        }
    }

    /// Forget the server's address while keeping the signed-in
    /// session. Entirely local — no server round-trip — so it works
    /// while the server is unreachable, which is precisely when it's
    /// needed. Access and refresh tokens are issued by the server and
    /// are not bound to its address, so re-pointing at the same server
    /// at a new address resumes the session without re-entering
    /// credentials.
    ///
    /// The stored TOFU fingerprint is kept deliberately: if the
    /// address the user types next belongs to a *different* server,
    /// the mismatch screen fires rather than silently accepting it.
    public func changeServer() {
        logger.info("changeServer: dropping stored endpoint, keeping session")
        refreshTask?.cancel()
        refreshTask = nil
        clearServerEndpoint()
        serverInfo = nil
        legalStatus = nil
        serverUnreachable = false
        error = nil
        Task {
            await logStreamer.stop()
            await grpcClient.close()
        }
        state = .needsServer
    }

    /// Re-attempt the round-trips that failed while the server was
    /// unreachable. Rebuilds the gRPC channel first — a channel that
    /// was created against a host that was down sits on a dead
    /// transport that won't heal on its own.
    public func retryConnection() async {
        let url: URL
        switch state {
        case .authenticated(let u), .needsLegalAgreement(let u), .needsLogin(let u):
            url = u
        default:
            return
        }
        logger.info("retryConnection: rebuilding channel and re-checking session")
        error = nil
        try? await grpcClient.reconnect()
        await performTokenRefresh()
        guard KeychainService.load(key: .accessToken) != nil else { return }
        await transitionAfterAuth(serverURL: url)
    }

    public func acceptFingerprintChange() async {
        KeychainService.delete(key: .serverFingerprint)
        if case .fingerprintMismatch(let url, _, _) = state {
            await connectToServer(urlString: url.absoluteString)
        }
    }

    /// Full local reset: endpoint, tokens, and the pinned server
    /// fingerprint all go. Use [changeServer] instead when the intent
    /// is to follow the same server to a new address — this one makes
    /// the user sign in again.
    public func disconnectServer() {
        refreshTask?.cancel()
        refreshTask = nil
        KeychainService.clearAll()
        serverInfo = nil
        legalDocs = nil
        legalStatus = nil
        awaitingBiometric = false
        biometricLoginEnabled = false
        serverUnreachable = false
        persistLegalDocs(nil)
        Task {
            await logStreamer.stop()
            await grpcClient.close()
        }
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
                await logStreamer.start()
                await refreshServerInfo()
            } else {
                logger.info("transitionAfterAuth: legal agreement required")
                state = .needsLegalAgreement(serverURL: serverURL)
            }
        } catch let rpcError as RPCError where rpcError.code == .unauthenticated {
            // The access token was rejected (revoked server-side, expired
            // beyond what performTokenRefresh could rescue, etc.). Don't
            // proceed to .authenticated — the user would land on empty
            // screens with no valid token. Force a clean logout.
            logger.info("transitionAfterAuth: legal-status returned UNAUTHENTICATED, logging out")
            await logout()
        } catch {
            // Network / unreachable / other transient — proceed
            // optimistically; the server's AuthInterceptor enforces
            // compliance on gated RPCs when we do reach it.
            logger.warning("transitionAfterAuth: legal check failed, proceeding: \(error.localizedDescription)")
            state = .authenticated(serverURL: serverURL)
            await refreshServerInfo()
        }
    }

    public func agreeToTerms() async {
        guard let status = legalStatus else { return }
        error = nil
        do {
            _ = try await grpcClient.agreeToTerms(
                privacyPolicyVersion: status.requiredPrivacyPolicyVersion,
                termsOfUseVersion: status.requiredTermsOfUseVersion)
            legalStatus = nil
            if case .needsLegalAgreement(let url) = state {
                state = .authenticated(serverURL: url)
                await logStreamer.start()
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
    public func authenticateWithBiometric() async {
        let context = LAContext()
        let reason = "Sign in to Household Disc Keeper"
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
        guard case .needsLogin(let url) = state else { return }
        await resumeStoredSession(serverURL: url)
    }

    /// Exposed for the offline-mode toggle path. When the user flips
    /// offline mode OFF, ContentView calls this so we don't wait up
    /// to 12 minutes for the scheduled refresh to fire — the next
    /// gRPC call lands with a fresh access token instead of getting
    /// kicked back as UNAUTHENTICATED.
    public func refreshTokenNow() async {
        await performTokenRefresh()
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

    public var cachedCapabilities: [String] {
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

    public func clearPasswordChangeRequired() {
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
            // Also re-adopt the server's canonical address. Its public
            // hostname can change (DDNS retired, domain renamed,
            // HAProxy moved) without the user re-running
            // connectToServer — before this, the app kept loading the
            // stale URL out of the Keychain forever and every request
            // (RPCs, AVPlayer streaming, album downloads, image
            // fetches) pointed at a hostname that no longer resolved.
            // Reachable enough to answer Discover means reachable
            // enough to be told where to go next.
            let discovery = DiscoverResponse(proto: protoDiscovery)
            if let secureUrl = discovery.secureUrl,
               !secureUrl.isEmpty,
               secureUrl.hasPrefix("https://"),
               let url = URL(string: secureUrl) {
                let stored = KeychainService.load(key: .serverURL)
                if stored != secureUrl {
                    storeServerEndpoint(url)
                    await configureClients(for: url)
                    logger.info("refreshLegalDocs: server endpoint moved — \(stored ?? "nil") → \(secureUrl)")
                }
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
