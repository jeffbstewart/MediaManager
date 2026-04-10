import SwiftUI

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

                Image(systemName: "person.circle")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)

                Text("Sign In")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text(serverURL.host() ?? serverURL.absoluteString)
                    .foregroundStyle(.secondary)

                VStack(spacing: 12) {
                    TextField("Username", text: $username)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    SecureField("Password", text: $password)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.password)
                        .onSubmit { login() }

                    Button(action: login) {
                        if isLoggingIn {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Sign In")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(username.isEmpty || password.isEmpty || isLoggingIn)

                    if authManager.awaitingBiometric {
                        Button {
                            Task { await authManager.authenticateWithBiometric() }
                        } label: {
                            Label("Sign in with \(AuthManager.biometricTypeName)",
                                  systemImage: biometricIcon)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }
                }
                .padding(.horizontal)

                if let error = authManager.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()

                Button("Use a different server") {
                    authManager.disconnectServer()
                }
                .font(.callout)
                .padding(.bottom)
            }
            .padding()
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
