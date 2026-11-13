import SwiftUI
import MediaManagerCore
import MediaManagerProtos

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

                MMBrandWordmark(size: 34)

                Text("Welcome")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)

                Text("Create your administrator account to get started.")
                    .font(MarqueeTheme.Fonts.body)
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(spacing: 12) {
                    TextField("Username", text: $username)
                        .textFieldStyle(.mmPill)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    TextField("Display Name", text: $displayName)
                        .textFieldStyle(.mmPill)
                        .textContentType(.name)

                    SecureField("Password", text: $password)
                        .textFieldStyle(.mmPill)
                        .textContentType(.newPassword)

                    SecureField("Confirm Password", text: $confirmPassword)
                        .textFieldStyle(.mmPill)
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
                    .buttonStyle(.mmPrimary)
                    .disabled(!isValid || isCreating)
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
