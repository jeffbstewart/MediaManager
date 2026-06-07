import SwiftUI

/// Houses build info plus app-and-server-side legal docs — the
/// surfaces a user only occasionally needs but should always be
/// able to find. Previously these were scattered between the
/// sidebar's bottom bar (build number) and ProfileView (legal
/// sections); the build number didn't merit the prominence and the
/// legal sections aren't really a profile concern.
struct AboutView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var profile: ProfileResponse?

    private var marketingVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }
    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    var body: some View {
        List {
            Section("Build") {
                LabeledContent("Version", value: marketingVersion)
                LabeledContent("Build", value: buildNumber)
            }

            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Link(destination: AppPolicyAgreement.privacyPolicyURL) {
                        Label("Privacy Policy", systemImage: "hand.raised")
                    }
                    Text("Agreed to version \(AppPolicyAgreement.currentVersion)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Link(destination: AppPolicyAgreement.termsOfServiceURL) {
                    Label("Terms of Service", systemImage: "doc.text")
                }
                Link(destination: AppPolicyAgreement.sourceCodeURL) {
                    Label("Open Source (MIT)", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            } header: {
                Text("App Legal")
            } footer: {
                Text("Agreement between you and the app distributor. Stored on this device only.")
                    .font(.caption2)
            }

            if let legal = authManager.legalDocs, legal.isConfigured {
                Section("Server Legal") {
                    if let url = legal.privacyPolicyURL_resolved {
                        VStack(alignment: .leading, spacing: 4) {
                            Link(destination: url) {
                                Label("Privacy Policy", systemImage: "hand.raised")
                            }
                            if let profile,
                               let version = profile.privacyPolicyVersion,
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
                            if let profile,
                               let version = profile.termsOfUseVersion,
                               let date = profile.termsOfUseAcceptedAt {
                                Text("Agreed to version \(version) on \(date.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("About")
        .task {
            // Profile only contributes the per-version "Agreed
            // to version X on DATE" captions for server legal —
            // when offline (profile RPC fails) the page still
            // renders the rest, just without those captions.
            profile = try? await dataModel.profile()
        }
    }
}
