import SwiftUI

struct RootView: View {
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        switch authManager.state {
        case .needsServer:
            ServerSetupView()
        case .needsLogin(let serverURL):
            LoginView(serverURL: serverURL)
        case .authenticated:
            ContentView()
        case .fingerprintMismatch(_, let expected, let received):
            FingerprintMismatchView(expected: expected, received: received)
        }
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
