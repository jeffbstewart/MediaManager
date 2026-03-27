import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    let serverURL: URL
    @State private var username = ""
    @State private var password = ""
    @State private var isLoggingIn = false
    @State private var agreedToPrivacy = false

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

                    if let privacyURL = PrivacyPolicy.url {
                        HStack {
                            Button {
                                agreedToPrivacy.toggle()
                            } label: {
                                Image(systemName: agreedToPrivacy ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(agreedToPrivacy ? .blue : .secondary)
                                    .font(.title3)
                            }
                            .buttonStyle(.plain)

                            HStack(spacing: 4) {
                                Text("I agree to the")
                                Link("Privacy Policy", destination: privacyURL)
                            }
                            .font(.callout)
                        }
                    }

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
                    .disabled(username.isEmpty || password.isEmpty || isLoggingIn || (PrivacyPolicy.url != nil && !agreedToPrivacy))
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

                VStack(spacing: 12) {
                    Button("Use a different server") {
                        authManager.disconnectServer()
                    }
                    .font(.callout)

                    if let privacyURL = PrivacyPolicy.url {
                        Link(destination: privacyURL) {
                            Label("Privacy Policy", systemImage: "hand.raised")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.bottom)
            }
            .padding()
            .navigationTitle("")
        }
    }

    private func login() {
        isLoggingIn = true
        Task {
            await authManager.login(username: username, password: password)
            isLoggingIn = false
        }
    }
}
