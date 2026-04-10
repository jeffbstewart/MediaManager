import SwiftUI

// ProfileResponse is defined in ProtoAdapters.swift

struct ProfileView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var profile: ProfileResponse?
    @State private var loading = true
    @State private var showChangePassword = false
    @State private var showSessions = false
    @State private var tvQuality: Int = 1
    @State private var loadTask: Task<Void, Never>?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let profile {
                List {
                    Section("Account") {
                        LabeledContent("Username", value: profile.username ?? "-")
                        LabeledContent("Display Name", value: profile.displayName ?? "-")
                        LabeledContent("Role", value: profile.roleDisplay)
                        if let ceiling = profile.ratingCeilingLabel {
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
                            Task { try? await dataModel.updateTvQuality(newValue) }
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

                    if AuthManager.isBiometricAvailable {
                        Section("Security") {
                            Toggle(
                                "Sign in with \(AuthManager.biometricTypeName)",
                                isOn: Binding(
                                    get: { authManager.biometricLoginEnabled },
                                    set: { authManager.biometricLoginEnabled = $0 }
                                )
                            )
                        }
                    }

                    if let legal = authManager.legalDocs, legal.isConfigured {
                        Section("Legal") {
                            if let url = legal.privacyPolicyURL_resolved {
                                VStack(alignment: .leading, spacing: 4) {
                                    Link(destination: url) {
                                        Label("Privacy Policy", systemImage: "hand.raised")
                                    }
                                    if let version = profile.privacyPolicyVersion,
                                       let date = profile.privacyPolicyAcceptedAt {
                                        Text("Agreed to version \(version) on \(date.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            if let url = legal.termsOfUseURL_resolved {
                                VStack(alignment: .leading, spacing: 4) {
                                    Link(destination: url) {
                                        Label("Terms of Use", systemImage: "doc.text")
                                    }
                                    if let version = profile.termsOfUseVersion,
                                       let date = profile.termsOfUseAcceptedAt {
                                        Text("Agreed to version \(version) on \(date.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
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
        .onAppear {
            guard profile == nil else { return }
            loadTask = Task { await loadProfile() }
        }
        .onDisappear {
            loadTask?.cancel()
            loadTask = nil
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
        do {
            let result = try await dataModel.profile()
            guard !Task.isCancelled else { return }
            profile = result
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            NSLog("MMAPP profile load failed: %@", error.localizedDescription)
        }
        tvQuality = profile?.liveTvMinQuality ?? 1
        loading = false
    }
}

struct ChangePasswordView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Current Password", text: $currentPassword)
                        .textContentType(.password)
                    SecureField("New Password", text: $newPassword)
                        .textContentType(.newPassword)
                    SecureField("Confirm New Password", text: $confirmPassword)
                        .textContentType(.newPassword)
                }

                if let error {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }
                }

                Section {
                    Button(saving ? "Saving..." : "Change Password") {
                        Task { await changePassword() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(currentPassword.isEmpty || newPassword.isEmpty ||
                              newPassword != confirmPassword || saving)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
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
            try await dataModel.changePassword(current: currentPassword, new: newPassword)
            dismiss()
        } catch APIClientError.httpError(_, let msg) {
            error = msg == "invalid_credentials" ? "Current password is incorrect" : msg
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}
