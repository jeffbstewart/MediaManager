import SwiftUI

struct RootView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var showOfflineOffer = false

    var body: some View {
        switch authManager.state {
        case .needsServer:
            ServerSetupView()
        case .needsLogin(let serverURL):
            LoginView(serverURL: serverURL)
        case .authenticated:
            ContentView()
                .sheet(isPresented: .init(
                    get: { authManager.passwordChangeRequired },
                    set: { _ in }
                )) {
                    ForcedPasswordChangeView()
                }
                .alert("Server Unreachable", isPresented: $showOfflineOffer) {
                    Button("Go Offline") {
                        dataModel.downloads.isOfflineMode = true
                    }
                    Button("Keep Trying", role: .cancel) {}
                } message: {
                    Text("Can't reach the server. You can switch to offline mode to browse and play your downloaded content.")
                }
                .onChange(of: authManager.serverUnreachable) { _, unreachable in
                    if unreachable && dataModel.downloads.hasCompletedDownloads && !dataModel.downloads.isOfflineMode {
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
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "lock.rotation")
                    .font(.system(size: 64))
                    .foregroundStyle(.orange)

                Text("Password Change Required")
                    .font(.title2)
                    .fontWeight(.bold)

                Text("Your administrator requires you to change your password before continuing.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(spacing: 12) {
                    SecureField("New Password", text: $newPassword)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.newPassword)

                    SecureField("Confirm Password", text: $confirmPassword)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.newPassword)

                    if let error {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }

                    Button(saving ? "Saving..." : "Change Password") {
                        Task { await changePassword() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(newPassword.isEmpty || newPassword != confirmPassword || saving)
                }
                .padding(.horizontal)

                Spacer()
                Spacer()
            }
            .interactiveDismissDisabled()
        }
    }

    private func changePassword() async {
        error = nil
        guard newPassword.count >= 8 else {
            error = "Password must be at least 8 characters"
            return
        }
        saving = true
        do {
            try await dataModel.changePassword(current: "", new: newPassword)
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
                    .font(.system(size: 64))
                    .foregroundStyle(.red)

                Text("Server Identity Changed")
                    .font(.title)
                    .fontWeight(.bold)

                Text("This server's identity has changed since you last connected. This could mean the server's signing key was rotated, or it could indicate a security issue.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Expected fingerprint:")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(expected.prefix(16) + "...")
                        .font(.system(.caption, design: .monospaced))

                    Text("Received fingerprint:")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(received.prefix(16) + "...")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.red)
                }
                .padding()
                .background(.fill.quaternary)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text("If you deliberately rotated the server's JWT signing key, tap \"I Rotated the Key\" to proceed. Otherwise, do not connect.")
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                Spacer()

                VStack(spacing: 12) {
                    Button("I Rotated the Key") {
                        Task { await authManager.acceptFingerprintChange() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    Button("Disconnect") {
                        authManager.disconnectServer()
                    }
                    .foregroundStyle(.red)
                }
                .padding(.bottom, 32)
            }
            .padding()
            .navigationTitle("")
        }
    }
}
