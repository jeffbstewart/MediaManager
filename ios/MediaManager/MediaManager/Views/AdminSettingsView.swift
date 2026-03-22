import SwiftUI

struct AdminSettingsView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var settings: [String: String] = [:]
    @State private var buddyKeys: [AdminBuddyKey] = []
    @State private var loading = true
    @State private var saving = false

    private let settingLabels: [(key: String, label: String)] = [
        ("nas_root_path", "NAS Root Path"),
        ("roku_base_url", "Roku Base URL"),
        ("personal_video_enabled", "Personal Videos Enabled"),
        ("personal_video_directory", "Personal Video Directory"),
        ("lease_duration_minutes", "Lease Duration (minutes)"),
        ("for_mobile_enabled", "ForMobile Enabled"),
        ("keepa_enabled", "Keepa Enabled"),
        ("keepa_tokens_per_minute", "Keepa Tokens/Minute"),
    ]

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else {
                List {
                    Section("Server Settings") {
                        ForEach(settingLabels, id: \.key) { item in
                            if isBoolSetting(item.key) {
                                Toggle(item.label, isOn: boolBinding(for: item.key))
                            } else {
                                LabeledContent(item.label, value: settings[item.key] ?? "-")
                            }
                        }
                    }

                    if !buddyKeys.isEmpty {
                        Section("Buddy Keys") {
                            ForEach(buddyKeys) { key in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(key.name)
                                        .fontWeight(.medium)
                                    if let created = key.createdAt {
                                        Text(created)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .task { await loadSettings() }
        .refreshable { await loadSettings() }
    }

    private func isBoolSetting(_ key: String) -> Bool {
        ["personal_video_enabled", "for_mobile_enabled", "keepa_enabled"].contains(key)
    }

    private func boolBinding(for key: String) -> Binding<Bool> {
        Binding(
            get: { settings[key] == "true" },
            set: { newValue in
                settings[key] = newValue ? "true" : "false"
                Task { await saveSetting(key, value: newValue ? "true" : "false") }
            }
        )
    }

    private func loadSettings() async {
        loading = settings.isEmpty
        let response: AdminSettingsResponse? = try? await authManager.apiClient.get("admin/settings")
        if let s = response?.settings {
            settings = s.compactMapValues { $0 }
        }
        buddyKeys = response?.buddyKeys ?? []
        loading = false
    }

    private func saveSetting(_ key: String, value: String) async {
        try? await authManager.apiClient.put("admin/settings", body: [key: value])
    }
}
