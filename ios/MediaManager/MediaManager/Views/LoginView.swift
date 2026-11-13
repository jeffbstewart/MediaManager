import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    let serverURL: URL
    @State private var username = ""
    @State private var password = ""
    @State private var isLoggingIn = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                MMBrandWordmark(size: 34)

                Text("Sign In")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .accessibilityIdentifier("login-title")

                if !SnapshotMode.isActive {
                    Text(serverURL.host() ?? serverURL.absoluteString)
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                }

                VStack(spacing: 12) {
                    TextField("Username", text: $username)
                        .textFieldStyle(.mmPill)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("login-username")

                    SecureField("Password", text: $password)
                        .textFieldStyle(.mmPill)
                        .textContentType(.password)
                        .onSubmit { login() }
                        .accessibilityIdentifier("login-password")

                    Button(action: login) {
                        if isLoggingIn {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Sign In")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.mmPrimary)
                    .disabled(username.isEmpty || password.isEmpty || isLoggingIn)
                    .accessibilityIdentifier("login-submit")

                    if authManager.awaitingBiometric {
                        Button {
                            Task { await authManager.authenticateWithBiometric() }
                        } label: {
                            Label("Sign in with \(AuthManager.biometricTypeName)",
                                  systemImage: biometricIcon)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.mmGhost)
                    }
                }
                .padding(.horizontal)

                if let error = authManager.error {
                    Text(error)
                        .foregroundStyle(MarqueeTheme.Colors.pink)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()

                Button("Use a different server") {
                    authManager.disconnectServer()
                }
                .font(MarqueeTheme.Fonts.control)
                .foregroundStyle(MarqueeTheme.Colors.text3)
                .padding(.bottom)
            }
            .padding()
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
            .navigationTitle("")
            .onAppear {
                if authManager.awaitingBiometric {
                    Task { await authManager.authenticateWithBiometric() }
                }
            }
        }
    }

    private var biometricIcon: String {
        AuthManager.biometricTypeName == "Face ID" ? "faceid" : "touchid"
    }

    private func login() {
        isLoggingIn = true
        Task {
            await authManager.login(username: username, password: password)
            isLoggingIn = false
        }
    }
}
