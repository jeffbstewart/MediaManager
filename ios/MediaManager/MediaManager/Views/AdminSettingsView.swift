import SwiftUI
import GRPCCore
import MediaManagerCore
import MediaManagerProtos

struct AdminSettingsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var settings: [String: String] = [:]
    /// The server's reason for refusing the last save of each key.
    @State private var fieldErrors: [String: String] = [:]
    @State private var buddyKeys: [AdminBuddyKey] = []
    @State private var loading = true
    @State private var statusMessage: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else {
                List {
                    Section(header: MMSectionHeader("Paths")) {
                        settingField("nas_root_path", label: "NAS Root Path")
                        settingField("ffmpeg_path", label: "FFmpeg Path")
                        settingField("public_base_url", label: "Public Server URL")
                    }
                    .mmRow()

                    Section(header: MMSectionHeader("Personal Videos")) {
                        settingToggle("personal_video_enabled", label: "Enable Personal Videos")
                        settingField("personal_video_nas_dir", label: "Personal Video Directory")
                    }
                    .mmRow()

                    Section(header: MMSectionHeader("Transcode Buddy")) {
                        settingField("buddy_lease_duration_minutes", label: "Lease Duration (min)")
                    }
                    .mmRow()

                    Section(header: MMSectionHeader("Keepa Price Lookup")) {
                        settingToggle("keepa_enabled", label: "Enable Keepa")
                        settingField("keepa_api_key", label: "Keepa API Key", secure: true)
                        settingField("keepa_tokens_per_minute", label: "Tokens/Minute")
                    }
                    .mmRow()

                    Section(
                        header: MMSectionHeader("Legal Documents"),
                        footer: Text(Self.legalHelp)
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                    ) {
                        settingField("privacy_policy_url", label: "Privacy Policy URL")
                        settingField("privacy_policy_version", label: "Privacy Policy Version")
                        settingField("ios_terms_of_use_url", label: "iOS Terms of Use URL")
                        settingField("ios_terms_of_use_version", label: "iOS Terms Version")
                        settingField("web_terms_of_use_url", label: "Web Terms of Use URL")
                        settingField("web_terms_of_use_version", label: "Web Terms Version")
                        settingField("android_tv_terms_of_use_url", label: "Android TV Terms of Use URL")
                        settingField("android_tv_terms_of_use_version", label: "Android TV Terms Version")
                    }
                    .mmRow()

                    if !buddyKeys.isEmpty {
                        Section(header: MMSectionHeader("Buddy Keys")) {
                            ForEach(buddyKeys) { key in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(key.name)
                                        .fontWeight(.medium)
                                    if let created = key.createdAt {
                                        Text(created)
                                            .font(MarqueeTheme.Fonts.meta)
                                            .foregroundStyle(MarqueeTheme.Colors.text3)
                                    }
                                }
                            }
                        }
                        .mmRow()
                    }

                    if let statusMessage {
                        Section {
                            Text(statusMessage)
                                .font(MarqueeTheme.Fonts.cardTitle)
                                .foregroundStyle(MarqueeTheme.Colors.green)
                        }
                        .mmRow()
                    }
                }
            }
        }
        .mmListChrome()
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
        VStack(alignment: .leading, spacing: 4) {
            if secure {
                SecureField(label, text: binding)
                    .onSubmit { Task { await saveSetting(key) } }
            } else {
                TextField(label, text: binding)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { Task { await saveSetting(key) } }
            }
            if let message = fieldErrors[key] {
                Text(message)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.pink)
                    .fixedSize(horizontal: false, vertical: true)
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
        do {
            let response = try await dataModel.adminSettings()
            settings = response.settings.compactMapValues { $0 }
            buddyKeys = response.buddyKeys
        } catch {
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }

    /// Saves one key. A refusal leaves the edit in the field and shows
    /// the server's reason under it; for the legal keys that reason
    /// names every field still to fill in.
    private func saveSetting(_ key: String) async {
        do {
            try await dataModel.updateSetting(key: key, value: settings[key] ?? "")
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            fieldErrors[key] = Self.refusalText(error)
            return
        }
        fieldErrors[key] = nil
        // The legal rule spans fields: one save can resolve the problem
        // another field was refused for, so a successful legal save
        // clears the stale refusals on its siblings.
        if Self.legalKeys.contains(key) {
            for legalKey in Self.legalKeys { fieldErrors[legalKey] = nil }
        }
        statusMessage = "Saved"
        try? await Task.sleep(for: .seconds(2))
        statusMessage = nil
    }

    private static let legalKeys: Set<String> = [
        "privacy_policy_url", "privacy_policy_version",
        "ios_terms_of_use_url", "ios_terms_of_use_version",
        "web_terms_of_use_url", "web_terms_of_use_version",
        "android_tv_terms_of_use_url", "android_tv_terms_of_use_version",
    ]

    private static let legalHelp = """
        Once the privacy policy has a version, every platform needs a \
        terms URL and a version of 1 or more. Enter about:blank for a \
        platform with no document. When turning requirements on, set \
        the privacy policy version last.
        """

    /// `UpdateSetting` answers INVALID_ARGUMENT with a description meant
    /// for the admin; anything else gets a plain sentence rather than
    /// the transport's "GRPCCore.RPCError" text.
    private static func refusalText(_ error: Error) -> String {
        guard let rpc = error as? RPCError else {
            return "Not saved: \(error.localizedDescription)"
        }
        switch rpc.code {
        case .invalidArgument, .failedPrecondition:
            return rpc.message.isEmpty ? "The server did not accept this value." : rpc.message
        case .permissionDenied:
            return "Not saved: only an admin can change settings."
        case .unavailable, .deadlineExceeded:
            return "Not saved: the server could not be reached. Try again."
        default:
            return "Not saved: the server reported an error. Try again."
        }
    }
}
