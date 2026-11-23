import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct TermsAgreementView: View {
    @Environment(AuthManager.self) private var authManager
    let serverURL: URL
    @State private var agreedToPrivacy = false
    @State private var agreedToTerms = false
    @State private var isSubmitting = false
    @State private var showDeleteAccount = false

    private var status: MMLegalStatusResponse? { authManager.legalStatus }

    private var privacyPolicyURL: URL? {
        guard let urlString = status?.privacyPolicyURL, !urlString.isEmpty else { return nil }
        return URL(string: urlString)
    }

    private var termsOfUseURL: URL? {
        guard let urlString = status?.termsOfUseURL, !urlString.isEmpty else { return nil }
        return URL(string: urlString)
    }

    private var allAgreed: Bool {
        (privacyPolicyURL == nil || agreedToPrivacy) &&
        (termsOfUseURL == nil || agreedToTerms)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                MMBrandWordmark(size: 34)

                Text("Terms & Privacy")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)

                if let problem = authManager.legalGateProblem {
                    // Agreeing again would not get past the server's
                    // gate, so offer no Continue to loop on. Sign out
                    // and change server stay below.
                    Text(problem)
                        .font(MarqueeTheme.Fonts.body)
                        .foregroundStyle(MarqueeTheme.Colors.text2)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                        .accessibilityIdentifier("server-terms-gate-problem")

                    Button {
                        isSubmitting = true
                        Task {
                            await authManager.retryLegalCheck()
                            isSubmitting = false
                        }
                    } label: {
                        if isSubmitting {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Try Again").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.mmPrimary)
                    .disabled(isSubmitting)
                    .padding(.horizontal)
                    .accessibilityIdentifier("server-terms-retry")
                } else {
                    agreementForm
                }

                Spacer()

                // Both are local-only, so this screen stays escapable
                // when the server is unreachable — otherwise agreeing
                // is the only way forward and agreeing needs the
                // server.
                HStack(spacing: 24) {
                    Button("Use a different server") {
                        authManager.changeServer()
                    }
                    Button("Sign out") {
                        Task { await authManager.logout() }
                    }
                }
                .font(MarqueeTheme.Fonts.control)
                .foregroundStyle(MarqueeTheme.Colors.text3)

                // Someone who won't agree to the terms must still be
                // able to leave; DeleteAccount is gate-exempt.
                Button("Delete account") {
                    showDeleteAccount = true
                }
                .font(MarqueeTheme.Fonts.control)
                .foregroundStyle(MarqueeTheme.Colors.text3)
                .accessibilityIdentifier("server-terms-delete-account")
                .padding(.bottom)
            }
            .padding()
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
            .navigationTitle(Text(verbatim: ""))
            .sheet(isPresented: $showDeleteAccount) {
                DeleteAccountView()
            }
        }
    }

    @ViewBuilder
    private var agreementForm: some View {
        Text("Please review and accept the following before continuing.")
            .font(MarqueeTheme.Fonts.body)
            .foregroundStyle(MarqueeTheme.Colors.text2)
            .multilineTextAlignment(.center)
            .padding(.horizontal)

        VStack(alignment: .leading, spacing: 16) {
            if let url = privacyPolicyURL {
                HStack {
                    Button {
                        agreedToPrivacy.toggle()
                    } label: {
                        Image(systemName: agreedToPrivacy ? "checkmark.square.fill" : "square")
                            .foregroundStyle(agreedToPrivacy ? MarqueeTheme.Colors.accent : MarqueeTheme.Colors.text3)
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("server-terms-privacy-checkbox")

                    HStack(spacing: 4) {
                        Text("I have read and agree to the")
                        Link("Privacy Policy", destination: url)
                    }
                    .font(.callout)
                }
            }

            if let url = termsOfUseURL {
                HStack {
                    Button {
                        agreedToTerms.toggle()
                    } label: {
                        Image(systemName: agreedToTerms ? "checkmark.square.fill" : "square")
                            .foregroundStyle(agreedToTerms ? MarqueeTheme.Colors.accent : MarqueeTheme.Colors.text3)
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("server-terms-terms-checkbox")

                    HStack(spacing: 4) {
                        Text("I have read and agree to the")
                        Link("Terms of Use", destination: url)
                    }
                    .font(.callout)
                }
            }
        }
        .padding(.horizontal)

        Button(action: submit) {
            if isSubmitting {
                ProgressView()
                    .frame(maxWidth: .infinity)
            } else {
                Text("Continue")
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.mmPrimary)
        .disabled(!allAgreed || isSubmitting)
        .padding(.horizontal)
        .accessibilityIdentifier("server-terms-continue")

        if let error = authManager.error {
            Text(error)
                .foregroundStyle(MarqueeTheme.Colors.pink)
                .font(.callout)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
    }

    private func submit() {
        isSubmitting = true
        Task {
            await authManager.agreeToTerms()
            isSubmitting = false
        }
    }
}
