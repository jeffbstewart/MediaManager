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
    @Environment(\.dynamicTypeSize) private var typeSize

    // The theme's body and control fonts are fixed sizes. This is the
    // screen where the user accepts terms, so here they follow the
    // user's text size like the rest of the copy on the page.
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 16
    @ScaledMetric(relativeTo: .subheadline) private var controlSize: CGFloat = 14

    /// Order matters here. The agreement sits directly under the two
    /// documents it refers to, so the action and what it accepts are
    /// read together. The note about server terms and the source link
    /// are supplementary and come last: when the page is taller than
    /// the screen (a short display, large text) the whole page
    /// scrolls, and what falls below the fold is the least important
    /// part, never the button.
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 12)

                MMBrandWordmark(size: 34)
                    .padding(.top)
                    .padding(.horizontal, 16)

                Text("Before You Continue")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .multilineTextAlignment(.center)

                Text("Household Disc Keeper is an app that connects you to a server you (or someone you trust) operates. Your data lives on that server, not in the cloud.")
                    .font(.system(size: bodySize))
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(alignment: .leading, spacing: 12) {
                    sectionHeader("App-Level Privacy Policy and Terms")
                    Text("This is the agreement between **you and the app distributor** — what the app maker does (and doesn't do) with anything we touch.")
                        .font(.subheadline)
                    // Side by side normally; stacked at the accessibility
                    // text sizes, where two columns break the titles
                    // mid-word.
                    let links = typeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(spacing: 12))
                        : AnyLayout(HStackLayout(spacing: 16))
                    links {
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
                .background(MarqueeTheme.Colors.scrim)
                .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: MarqueeTheme.Radius.hero, style: .continuous)
                        .strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
                .padding(.horizontal, 16)

                VStack(spacing: 8) {
                    Button {
                        agreement.accept()
                    } label: {
                        Text("I Agree")
                            .font(.system(size: controlSize, weight: .semibold))
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.mmPrimary)
                    .padding(.horizontal, 24)
                    .accessibilityIdentifier("app-policy-agree")

                    Text("By tapping I Agree, you acknowledge that you've reviewed both documents and accept their terms.")
                        .font(.caption2)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 32)
                }

                VStack(alignment: .leading, spacing: 8) {
                    sectionHeader("A note about server terms")
                    Text("On the next page, you'll pick the server to connect to. That server may have its own privacy policy and terms and conditions you will have to agree to in order to use the server. Those are different, and we'll show them to you on a later page.")
                        .font(.footnote)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 8)

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

                Spacer(minLength: 24)
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .mmGlowPage()
    }

    @ViewBuilder
    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(MarqueeTheme.Fonts.h3)
            .foregroundStyle(MarqueeTheme.Colors.text)
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
            .background(MarqueeTheme.Colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: MarqueeTheme.Radius.card, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
