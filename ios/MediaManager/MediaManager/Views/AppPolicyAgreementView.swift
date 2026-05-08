import SwiftUI

/// First-launch gate: user must accept the *app's* Privacy Policy +
/// Terms of Service before any other UI renders. Distinct from the
/// server-side legal agreement (TermsAgreementView) — this one is
/// per-device, stored in UserDefaults, never transmitted to any
/// server. The split is explained in the body copy so users
/// understand why they'll see a second agreement screen later when
/// they connect to a server.
struct AppPolicyAgreementView: View {
    @Environment(AppPolicyAgreement.self) private var agreement
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 40)

                Image(systemName: "hand.raised.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(.tint)
                    .padding(.top)

                Text("Before You Continue")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)

                Text("Household Disc Keeper is an app that connects you to a server you (or someone you trust) operates. Your data lives on that server, not in the cloud.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(alignment: .leading, spacing: 12) {
                    sectionHeader("App-Level Privacy Policy and Terms")
                    Text("This is the agreement between **you and the app distributor** — what the app maker does (and doesn't do) with anything we touch.")
                        .font(.subheadline)
                    HStack(spacing: 16) {
                        documentLink(
                            title: "Privacy Policy",
                            icon: "lock.shield",
                            url: AppPolicyAgreement.privacyPolicyURL)
                        documentLink(
                            title: "Terms of Service",
                            icon: "doc.text",
                            url: AppPolicyAgreement.termsOfServiceURL)
                    }
                }
                .padding(16)
                .background(.fill.quaternary)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)

                VStack(alignment: .leading, spacing: 8) {
                    sectionHeader("A note about server terms")
                    Text("On the next page, you'll pick the server to connect to. That server may have its own privacy policy and terms and conditions you will have to agree to in order to use the server. Those are different, and we'll show them to you on a later page.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 24)

                Spacer(minLength: 24)

                VStack(spacing: 8) {
                    Button {
                        agreement.accept()
                    } label: {
                        Text("I Agree")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .padding(.horizontal, 24)

                    Text("By tapping I Agree, you acknowledge that you've reviewed both documents and accept their terms.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)

                    // Source / license tertiary link. Subtle on
                    // purpose — transparency for the curious, plus a
                    // visible reminder of the MIT license's
                    // no-warranty clause for the audit trail.
                    Button {
                        openURL(AppPolicyAgreement.sourceCodeURL)
                    } label: {
                        Text("Open source under the MIT License — view source")
                            .font(.caption2)
                            .underline()
                            .foregroundStyle(.tint)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }

                Spacer(minLength: 32)
            }
        }
    }

    @ViewBuilder
    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.headline)
    }

    /// Tappable card for one of the two document URLs. Opens in the
    /// system browser via the SwiftUI openURL action — for the
    /// hosted-by-us privacy policy / ToS, that's the right
    /// affordance (in-app SafariView is overkill for a static page
    /// the user reviews once).
    @ViewBuilder
    private func documentLink(title: String, icon: String, url: URL) -> some View {
        Button {
            openURL(url)
        } label: {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.title2)
                Text(title)
                    .font(.subheadline.weight(.medium))
                Text("View")
                    .font(.caption2)
                    .foregroundStyle(.tint)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(.background)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }
}
