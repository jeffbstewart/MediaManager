import SwiftUI

struct SetupAccountView: View {
    @Environment(AuthManager.self) private var authManager
    let serverURL: URL
    @State private var username = ""
    @State private var displayName = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var isCreating = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                Image(systemName: "film.stack")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)

                Text("Welcome")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Create your administrator account to get started.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(spacing: 12) {
                    TextField("Username", text: $username)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    TextField("Display Name", text: $displayName)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.name)

                    SecureField("Password", text: $password)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.newPassword)

                    SecureField("Confirm Password", text: $confirmPassword)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.newPassword)

                    Button(action: createAccount) {
                        if isCreating {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Create Account")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!isValid || isCreating)
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
        }
    }

    private var isValid: Bool {
        !username.isEmpty && !displayName.isEmpty &&
        !password.isEmpty && password == confirmPassword
    }

    private func createAccount() {
        isCreating = true
        Task {
            await authManager.createFirstUser(
                username: username, password: password, displayName: displayName)
            isCreating = false
        }
    }
}
