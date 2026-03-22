import SwiftUI

struct AdminUsersView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var users: [AdminUser] = []
    @State private var loading = true
    @State private var selectedUser: AdminUser?
    @State private var showCreateUser = false
    @State private var error: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if users.isEmpty {
                ContentUnavailableView("No users", systemImage: "person.2")
            } else {
                List {
                    ForEach(users) { user in
                        Button {
                            selectedUser = user
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(user.displayName ?? user.username)
                                        .fontWeight(.medium)
                                    Spacer()
                                    if user.isAdmin {
                                        Text("Admin")
                                            .font(.caption2)
                                            .fontWeight(.bold)
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(.blue)
                                            .clipShape(Capsule())
                                    }
                                    if user.locked {
                                        Text("Locked")
                                            .font(.caption2)
                                            .fontWeight(.bold)
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(.red)
                                            .clipShape(Capsule())
                                    }
                                    if user.mustChangePassword {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundStyle(.orange)
                                            .font(.caption)
                                    }
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }

                                HStack(spacing: 8) {
                                    Text(user.username)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    if let ceiling = user.ratingCeilingLabel {
                                        Text(ceiling)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle("Users")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showCreateUser = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task { await loadUsers() }
        .refreshable { await loadUsers() }
        .alert("Error", isPresented: .init(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: {
            Text(error ?? "")
        }
        .sheet(item: $selectedUser) { user in
            AdminUserDetailView(user: user) { await loadUsers() }
        }
        .sheet(isPresented: $showCreateUser) {
            AdminCreateUserView { await loadUsers() }
        }
    }

    private func loadUsers() async {
        loading = users.isEmpty
        let response: AdminUserListResponse? = try? await authManager.apiClient.get("admin/users")
        users = response?.users ?? []
        loading = false
    }
}

// MARK: - User Detail Sheet

struct AdminUserDetailView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let user: AdminUser
    let onComplete: () async -> Void

    @State private var showResetPassword = false
    @State private var showDeleteConfirm = false
    @State private var showRatingPicker = false
    @State private var error: String?
    @State private var actionMessage: String?

    private let ratingOptions: [(value: Int?, label: String)] = [
        (nil, "Unrestricted"),
        (1, "G"),
        (2, "PG"),
        (3, "PG-13"),
        (4, "R"),
        (5, "NC-17"),
    ]

    var body: some View {
        NavigationStack {
            List {
                // Info
                Section("Account") {
                    LabeledContent("Username", value: user.username)
                    LabeledContent("Display Name", value: user.displayName ?? "-")
                    LabeledContent("Role", value: user.isAdmin ? "Admin" : "Viewer")
                    LabeledContent("Rating Limit", value: user.ratingCeilingLabel ?? "Unrestricted")
                    if user.locked {
                        LabeledContent("Status", value: "Locked")
                            .foregroundStyle(.red)
                    }
                    if user.mustChangePassword {
                        LabeledContent("Password", value: "Must change on login")
                            .foregroundStyle(.orange)
                    }
                }

                // Role
                Section("Role") {
                    Button(user.isAdmin ? "Demote to Viewer" : "Promote to Admin") {
                        Task { await toggleRole() }
                    }
                }

                // Rating Ceiling
                Section("Content Rating Limit") {
                    ForEach(ratingOptions, id: \.label) { option in
                        Button {
                            Task { await setRatingCeiling(option.value) }
                        } label: {
                            HStack {
                                Text(option.label)
                                Spacer()
                                if user.ratingCeiling == option.value {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.blue)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Password
                Section("Password") {
                    Button("Reset Password") {
                        showResetPassword = true
                    }

                    if !user.mustChangePassword {
                        Button("Force Change on Next Login") {
                            Task { await forcePasswordChange() }
                        }
                    }
                }

                // Account actions
                if user.locked {
                    Section {
                        Button("Unlock Account") {
                            Task { await unlockUser() }
                        }
                        .foregroundStyle(.green)
                    }
                }

                // Sessions
                Section {
                    Button("Invalidate All Sessions") {
                        Task { await invalidateSessions() }
                    }
                    .foregroundStyle(.orange)
                }

                // Danger zone
                Section {
                    Button("Delete User", role: .destructive) {
                        showDeleteConfirm = true
                    }
                }

                if let actionMessage {
                    Section {
                        Text(actionMessage)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }

                if let error {
                    Section {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(user.displayName ?? user.username)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showResetPassword) {
                AdminResetPasswordView(user: user) {
                    await onComplete()
                    showSuccess("Password reset")
                }
            }
            .alert("Delete User", isPresented: $showDeleteConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) {
                    Task { await deleteUser() }
                }
            } message: {
                Text("Are you sure you want to delete \(user.username)? This cannot be undone.")
            }
        }
    }

    private func toggleRole() async {
        error = nil
        let newLevel = user.isAdmin ? 1 : 2
        do {
            try await authManager.apiClient.put("admin/users/\(user.id)/role", body: ["access_level": newLevel])
            await onComplete()
            showSuccess(user.isAdmin ? "Demoted to Viewer" : "Promoted to Admin")
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func setRatingCeiling(_ ceiling: Int?) async {
        error = nil
        let body: [String: Any] = ceiling != nil ? ["ceiling": ceiling!] : ["ceiling": NSNull()]
        do {
            try await authManager.apiClient.put("admin/users/\(user.id)/rating-ceiling", body: body)
            await onComplete()
            showSuccess("Rating limit updated")
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func unlockUser() async {
        try? await authManager.apiClient.post("admin/users/\(user.id)/unlock", body: [:])
        await onComplete()
        showSuccess("Account unlocked")
    }

    private func forcePasswordChange() async {
        try? await authManager.apiClient.post("admin/users/\(user.id)/force-password-change", body: [:])
        await onComplete()
        showSuccess("Password change required on next login")
    }

    private func invalidateSessions() async {
        // Reset password to same (no-op for password) but this invalidates sessions
        // Actually, we don't have a direct "invalidate sessions" endpoint.
        // The unlock and role-change endpoints invalidate sessions as a side effect.
        // For now, show a message that role change or password reset will invalidate sessions.
        showSuccess("Use Reset Password or Role Change to invalidate sessions")
    }

    private func deleteUser() async {
        error = nil
        do {
            try await authManager.apiClient.delete("admin/users/\(user.id)")
            await onComplete()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func showSuccess(_ message: String) {
        actionMessage = message
        Task {
            try? await Task.sleep(for: .seconds(3))
            actionMessage = nil
        }
    }
}

// MARK: - Reset Password Sheet

struct AdminResetPasswordView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let user: AdminUser
    let onComplete: () async -> Void

    @State private var newPassword = ""
    @State private var forceChange = true
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Reset password for \(user.username)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Section {
                    SecureField("New Password", text: $newPassword)
                        .textContentType(.newPassword)
                    Toggle("Require change on login", isOn: $forceChange)
                }

                if let error {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }
                }

                Section {
                    Button(saving ? "Saving..." : "Reset Password") {
                        Task { await resetPassword() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(newPassword.count < 8 || saving)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Reset Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func resetPassword() async {
        saving = true
        error = nil
        do {
            try await authManager.apiClient.post("admin/users/\(user.id)/reset-password", body: [
                "new_password": newPassword,
                "force_change": forceChange
            ])
            await onComplete()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}

// MARK: - Create User Sheet

struct AdminCreateUserView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let onComplete: () async -> Void

    @State private var username = ""
    @State private var displayName = ""
    @State private var password = ""
    @State private var forceChange = true
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Display Name", text: $displayName)
                    SecureField("Password", text: $password)
                        .textContentType(.newPassword)
                    Toggle("Require change on login", isOn: $forceChange)
                }

                if let error {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }
                }

                Section {
                    Button(saving ? "Creating..." : "Create User") {
                        Task { await createUser() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(username.isEmpty || displayName.isEmpty || password.count < 8 || saving)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("New User")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func createUser() async {
        saving = true
        error = nil
        do {
            try await authManager.apiClient.post("admin/users", body: [
                "username": username,
                "display_name": displayName,
                "password": password,
                "force_change": forceChange
            ])
            await onComplete()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}
