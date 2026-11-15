import SwiftUI
import MediaManagerCore

/// Deletes the signed-in account (App Store Review Guideline 5.1.1(v)).
/// Presented as a sheet from Profile, the server terms screen and the
/// forced password change, so someone who won't agree to the terms or
/// owes a new password can still leave.
///
/// Asks for the current password, which the server requires. Then one
/// of two alerts: success, whose OK clears this device's copy of the
/// account and returns to sign-in; or failure, whose OK returns to the
/// screen the sheet was opened from.
struct DeleteAccountView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var deleting = false
    @State private var outcome: Outcome?

    private enum Outcome: Identifiable {
        case deleted
        case failed(String)
        var id: String {
            switch self {
            case .deleted: "deleted"
            case .failed(let message): "failed:\(message)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("This permanently deletes your account on this server, with your watch and reading progress, wishes, playlists, sessions and passkeys. It can't be undone.")
                        .font(MarqueeTheme.Fonts.body)
                        .foregroundStyle(MarqueeTheme.Colors.text2)
                    Text("Downloads and saved sign-in on this device are removed too.")
                        .font(MarqueeTheme.Fonts.body)
                        .foregroundStyle(MarqueeTheme.Colors.text2)
                }
                .mmRow()

                Section(header: MMSectionHeader("Confirm with your password")) {
                    SecureField("Current Password", text: $password)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .onSubmit { submit() }
                        .accessibilityIdentifier("delete-account-password")
                }
                .mmRow()

                Section {
                    Button(role: .destructive) {
                        submit()
                    } label: {
                        if deleting {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Delete My Account").frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(password.isEmpty || deleting)
                    .accessibilityIdentifier("delete-account-confirm")
                }
                .mmRow()
            }
            .mmListChrome()
            .navigationTitle("Delete Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(deleting)
                }
            }
            .interactiveDismissDisabled(deleting || outcome != nil)
            .alert(item: $outcome) { outcome in
                switch outcome {
                case .deleted:
                    Alert(
                        title: Text("Account Deleted"),
                        message: Text("Your account has been deleted from the server. Press OK to clear it from this device and return to sign-in."),
                        dismissButton: .default(Text("OK")) {
                            Task { await authManager.completeAccountDeletion() }
                        })
                case .failed(let message):
                    Alert(
                        title: Text("Account Not Deleted"),
                        message: Text(message),
                        dismissButton: .default(Text("OK")) { dismiss() })
                }
            }
        }
    }

    private func submit() {
        guard !password.isEmpty, !deleting else { return }
        deleting = true
        Task {
            do {
                try await authManager.deleteAccount(currentPassword: password)
                outcome = .deleted
            } catch {
                outcome = .failed(error.localizedDescription)
            }
            password = ""
            deleting = false
        }
    }
}
