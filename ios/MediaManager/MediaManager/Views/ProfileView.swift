import SwiftUI

struct ProfileResponse: Codable {
    let username: String
    let displayName: String
    let role: String
    let ratingCeiling: String?
    let liveTvMinQuality: Int

    enum CodingKeys: String, CodingKey {
        case username, role
        case displayName = "display_name"
        case ratingCeiling = "rating_ceiling"
        case liveTvMinQuality = "live_tv_min_quality"
    }
}

struct ProfileView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var profile: ProfileResponse?
    @State private var loading = true
    @State private var showChangePassword = false
    @State private var showSessions = false
    @State private var tvQuality: Int = 1

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let profile {
                List {
                    Section("Account") {
                        LabeledContent("Username", value: profile.username)
                        LabeledContent("Display Name", value: profile.displayName)
                        LabeledContent("Role", value: profile.role)
                        if let ceiling = profile.ratingCeiling {
                            LabeledContent("Rating Limit", value: ceiling)
                        }
                    }

                    Section("Live TV Quality") {
                        Picker("Minimum Quality", selection: $tvQuality) {
                            ForEach(1...5, id: \.self) { q in
                                Text(String(repeating: "\u{2605}", count: q) +
                                     String(repeating: "\u{2606}", count: 5 - q))
                                    .tag(q)
                            }
                        }
                        .onChange(of: tvQuality) { _, newValue in
                            Task { await updateTvQuality(newValue) }
                        }
                    }

                    Section {
                        Button("Change Password") {
                            showChangePassword = true
                        }

                        Button("Active Sessions") {
                            showSessions = true
                        }
                    }

                    Section {
                        Button("Sign Out", role: .destructive) {
                            Task { await authManager.logout() }
                        }
                    }
                }
            }
        }
        .navigationTitle("Profile")
        .task {
            await loadProfile()
        }
        .sheet(isPresented: $showChangePassword) {
            ChangePasswordView()
        }
        .sheet(isPresented: $showSessions) {
            SessionsView()
        }
    }

    private func loadProfile() async {
        loading = true
        profile = try? await authManager.apiClient.get("profile")
        tvQuality = profile?.liveTvMinQuality ?? 1
        loading = false
    }

    private func updateTvQuality(_ quality: Int) async {
        try? await authManager.apiClient.put("profile/tv-quality", body: ["min_quality": quality])
    }
}

struct ChangePasswordView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                SecureField("Current Password", text: $currentPassword)
                    .textContentType(.password)
                SecureField("New Password", text: $newPassword)
                    .textContentType(.newPassword)
                SecureField("Confirm New Password", text: $confirmPassword)
                    .textContentType(.newPassword)

                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.callout)
                }

                Button(saving ? "Saving..." : "Change Password") {
                    Task { await changePassword() }
                }
                .disabled(currentPassword.isEmpty || newPassword.isEmpty ||
                          newPassword != confirmPassword || saving)
            }
            .navigationTitle("Change Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func changePassword() async {
        error = nil
        guard newPassword == confirmPassword else {
            error = "Passwords don't match"
            return
        }
        guard newPassword.count >= 8 else {
            error = "Password must be at least 8 characters"
            return
        }

        saving = true
        do {
            try await authManager.apiClient.post("auth/change-password", body: [
                "current_password": currentPassword,
                "new_password": newPassword
            ])
            dismiss()
        } catch APIClientError.httpError(_, let msg) {
            error = msg == "invalid_credentials" ? "Current password is incorrect" : msg
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}
