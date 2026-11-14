import SwiftUI
import MediaManagerCore
import MediaManagerProtos

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
    @State private var screenshotModeNotice: String?

    private var marketingVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }
    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    var body: some View {
        List {
            Section(header: MMSectionHeader("Build")) {
                LabeledContent("Version", value: marketingVersion)
                LabeledContent("Build", value: buildNumber)
                    // Hidden switch for screenshot mode (see
                    // `ScreenshotMode`): four taps on the build row.
                    // Deliberately unlabelled; it is a tool for
                    // preparing store screenshots, not a feature.
                    .contentShape(Rectangle())
                    .onTapGesture(count: 4) {
                        let on = !ScreenshotMode.stored
                        ScreenshotMode.setStored(on)
                        screenshotModeNotice = on
                            ? "Screenshot mode will be on the next time the app starts. Names and artwork are replaced with invented ones. Do not edit anything while it is on."
                            : "Screenshot mode will be off the next time the app starts."
                    }
            }
            .mmRow()
            .alert("Screenshot Mode", isPresented: Binding(
                get: { screenshotModeNotice != nil },
                set: { if !$0 { screenshotModeNotice = nil } })
            ) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(screenshotModeNotice ?? "")
            }

            Section(header: MMSectionHeader("Credits")) {
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                    .font(.footnote)
            }
            .mmRow()

            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Link(destination: AppPolicyAgreement.privacyPolicyURL) {
                        Label("Privacy Policy", systemImage: "hand.raised")
                    }
                    Text("Agreed to version \(AppPolicyAgreement.currentVersion)")
                        .font(.caption)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                }
                Link(destination: AppPolicyAgreement.termsOfServiceURL) {
                    Label("Terms of Service", systemImage: "doc.text")
                }
                Link(destination: AppPolicyAgreement.sourceCodeURL) {
                    Label("Open Source (MIT)", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            } header: { MMSectionHeader("App Legal") } footer: {
                Text("Agreement between you and the app distributor. Stored on this device only.")
                    .font(.caption2)
            }
            .mmRow()

            if let legal = authManager.legalDocs, legal.isConfigured {
                Section(header: MMSectionHeader("Server Legal")) {
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
                                    .foregroundStyle(MarqueeTheme.Colors.text3)
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
                                    .foregroundStyle(MarqueeTheme.Colors.text3)
                            }
                        }
                    }
                }
                .mmRow()
            }
        }
        .mmListChrome()
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
