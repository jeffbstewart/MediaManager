import SwiftUI

struct AdminSettingsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var settings: [String: String] = [:]
    @State private var buddyKeys: [AdminBuddyKey] = []
    @State private var loading = true
    @State private var statusMessage: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else {
                List {
                    Section("Paths") {
                        settingField("nas_root_path", label: "NAS Root Path")
                        settingField("ffmpeg_path", label: "FFmpeg Path")
                        settingField("roku_base_url", label: "Roku Base URL")
                    }

                    Section("Personal Videos") {
                        settingToggle("personal_video_enabled", label: "Enable Personal Videos")
                        settingField("personal_video_nas_dir", label: "Personal Video Directory")
                    }

                    Section("Transcode Buddy") {
                        settingField("buddy_lease_duration_minutes", label: "Lease Duration (min)")
                    }

                    Section("Keepa Price Lookup") {
                        settingToggle("keepa_enabled", label: "Enable Keepa")
                        settingField("keepa_api_key", label: "Keepa API Key", secure: true)
                        settingField("keepa_tokens_per_minute", label: "Tokens/Minute")
                    }

                    Section("Legal Documents") {
                        settingField("privacy_policy_url", label: "Privacy Policy URL")
                        settingField("privacy_policy_version", label: "Privacy Policy Version")
                        settingField("ios_terms_of_use_url", label: "iOS Terms of Use URL")
                        settingField("ios_terms_of_use_version", label: "iOS Terms Version")
                        settingField("web_terms_of_use_url", label: "Web Terms of Use URL")
                        settingField("web_terms_of_use_version", label: "Web Terms Version")
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

                    if let statusMessage {
                        Section {
                            Text(statusMessage)
                                .font(.callout)
                                .foregroundStyle(.green)
                        }
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .task { await loadSettings() }
        .refreshable { await loadSettings() }
    }

    @ViewBuilder
    private func settingField(_ key: String, label: String, secure: Bool = false) -> some View {
        let binding = Binding<String>(
            get: { settings[key] ?? "" },
            set: { newValue in
                settings[key] = newValue
            }
        )
        HStack {
            if secure {
                SecureField(label, text: binding)
                    .onSubmit { Task { await saveSetting(key) } }
            } else {
                TextField(label, text: binding)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { Task { await saveSetting(key) } }
            }
        }
    }

    @ViewBuilder
    private func settingToggle(_ key: String, label: String) -> some View {
        Toggle(label, isOn: Binding(
            get: { settings[key] == "true" },
            set: { newValue in
                settings[key] = newValue ? "true" : "false"
                Task { await saveSetting(key) }
            }
        ))
    }

    private func loadSettings() async {
        loading = settings.isEmpty
        let response = try? await dataModel.adminSettings()
        if let s = response?.settings {
            settings = s.compactMapValues { $0 }
        }
        buddyKeys = response?.buddyKeys ?? []
        loading = false
    }

    private func saveSetting(_ key: String) async {
        try? await dataModel.updateSetting(key: key, value: settings[key] ?? "")
        statusMessage = "Saved"
        try? await Task.sleep(for: .seconds(2))
        statusMessage = nil
    }
}
