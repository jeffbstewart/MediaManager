import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct RootView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var showOfflineOffer = false

    /// True only while the user is past every auth/legal gate.
    /// Derived (not stored) so it tracks `authManager.state` without
    /// needing AuthState to be Equatable.
    private var isAuthed: Bool {
        if case .authenticated = authManager.state { return true }
        return false
    }

    private var unreachableMessage: String {
        let base = "Can't reach the server. If its address changed, choose Change Server and enter the new one — you'll stay signed in."
        guard dataModel.downloads.hasCompletedDownloads else { return base }
        return base + "\n\nOr switch to offline mode to browse and play your downloaded content."
    }

    var body: some View {
        content
            .onChange(of: isAuthed, initial: true) { _, authed in
                // When the user is not authenticated, the offline-mode
                // gate would block the login / setup / discover RPCs
                // they need to get back in. The toggle's reason to
                // exist is "I'm browsing my downloads instead of the
                // server" — meaningless when there's no session. Clear
                // it so a previous session's offline preference doesn't
                // strand them at the login screen.
                if !authed && dataModel.downloads.isOfflineMode {
                    dataModel.downloads.isOfflineMode = false
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch authManager.state {
        case .restoring:
            // Brief gap on every launch while AuthManager's async
            // Task settles whether we have valid tokens. Showing a
            // spinner here avoids a flash of ServerSetupView when
            // the user is in fact already logged in.
            VStack(spacing: 24) {
                MMBrandWordmark(size: 34)
                ProgressView().controlSize(.large)
                Text("Connecting…")
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
        case .needsServer:
            ServerSetupView()
        case .needsSetup(let serverURL):
            SetupAccountView(serverURL: serverURL)
        case .needsLogin(let serverURL):
            LoginView(serverURL: serverURL)
        case .needsLegalAgreement(let serverURL):
            TermsAgreementView(serverURL: serverURL)
        case .authenticated:
            ContentView()
                .sheet(isPresented: .init(
                    get: { authManager.passwordChangeRequired },
                    set: { _ in }
                )) {
                    ForcedPasswordChangeView()
                }
                .alert("Server Unreachable", isPresented: $showOfflineOffer) {
                    Button("Retry") {
                        Task { await authManager.retryConnection() }
                    }
                    if dataModel.downloads.hasCompletedDownloads {
                        Button("Go Offline") {
                            dataModel.downloads.isOfflineMode = true
                        }
                    }
                    // Both of these are local-only, so they work with
                    // the server dark — which is the whole point. Change
                    // Server keeps the session: tokens are issued by the
                    // server, not tied to its address, so re-pointing at
                    // the same server picks up where it left off.
                    Button("Change Server") {
                        authManager.changeServer()
                    }
                    Button("Sign Out", role: .destructive) {
                        Task { await authManager.logout() }
                    }
                    Button("Keep Trying", role: .cancel) {}
                } message: {
                    Text(unreachableMessage)
                }
                .onChange(of: authManager.serverUnreachable) { _, unreachable in
                    if unreachable && !dataModel.downloads.isOfflineMode {
                        showOfflineOffer = true
                    }
                }
        case .fingerprintMismatch(_, let expected, let received):
            FingerprintMismatchView(expected: expected, received: received)
        }
    }
}

struct ForcedPasswordChangeView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AuthManager.self) private var authManager
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "lock.rotation")
                    .font(.system(size: 56))
                    .foregroundStyle(MarqueeTheme.Colors.accent)

                Text("Password Change Required")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .multilineTextAlignment(.center)

                Text("Your administrator requires you to change your password before continuing. Enter the temporary password you were given, then pick a new one.")
                    .font(MarqueeTheme.Fonts.body)
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(spacing: 12) {
                    SecureField("Current Password", text: $currentPassword)
                        .textFieldStyle(.mmPill)
                        .textContentType(.password)

                    SecureField("New Password", text: $newPassword)
                        .textFieldStyle(.mmPill)
                        .textContentType(.newPassword)

                    SecureField("Confirm New Password", text: $confirmPassword)
                        .textFieldStyle(.mmPill)
                        .textContentType(.newPassword)

                    if let error {
                        Text(error)
                            .foregroundStyle(MarqueeTheme.Colors.pink)
                            .font(.callout)
                            .multilineTextAlignment(.center)
                    }

                    Button {
                        Task { await changePassword() }
                    } label: {
                        Text(saving ? "Saving..." : "Change Password")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.mmPrimary)
                    .disabled(currentPassword.isEmpty
                              || newPassword.isEmpty
                              || newPassword != confirmPassword
                              || saving)
                }
                .padding(.horizontal)

                Spacer()
                Spacer()
            }
            .padding()
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
            .interactiveDismissDisabled()
        }
    }

    private func changePassword() async {
        error = nil
        guard newPassword.count >= 8 else {
            error = "New password must be at least 8 characters"
            return
        }
        saving = true
        do {
            // ChangePassword always requires the current password,
            // even on the forced-change path — the server treats it
            // as a fresh credential proof so a borrowed unlocked
            // phone can't rotate the password to lock the real owner
            // out. Matches the web flow.
            try await dataModel.changePassword(
                current: currentPassword, new: newPassword)
            authManager.clearPasswordChangeRequired()
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}

struct FingerprintMismatchView: View {
    @Environment(AuthManager.self) private var authManager
    let expected: String
    let received: String

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(MarqueeTheme.Colors.live)

                Text("Server Identity Changed")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .multilineTextAlignment(.center)

                Text("This server's identity has changed since you last connected. This could mean the server's signing key was rotated, or it could indicate a security issue.")
                    .font(MarqueeTheme.Fonts.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .padding(.horizontal)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Expected fingerprint:")
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                    Text(expected.prefix(16) + "...")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(MarqueeTheme.Colors.text)

                    Text("Received fingerprint:")
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                    Text(received.prefix(16) + "...")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(MarqueeTheme.Colors.pink)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(MarqueeTheme.Colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous))

                Text("If you deliberately rotated the server's JWT signing key, tap \"I Rotated the Key\" to proceed. Otherwise, do not connect.")
                    .font(MarqueeTheme.Fonts.cardTitle)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
                    .padding(.horizontal)

                Spacer()

                VStack(spacing: 12) {
                    Button {
                        Task { await authManager.acceptFingerprintChange() }
                    } label: {
                        Text("I Rotated the Key").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.mmPrimary)

                    Button {
                        authManager.disconnectServer()
                    } label: {
                        Text("Disconnect").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.mmGhost)
                    .foregroundStyle(MarqueeTheme.Colors.pink)
                }
                .padding(.bottom, 32)
            }
            .padding()
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
            .navigationTitle("")
        }
    }
}
