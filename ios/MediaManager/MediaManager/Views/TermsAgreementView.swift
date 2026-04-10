import SwiftUI

struct TermsAgreementView: View {
    @Environment(AuthManager.self) private var authManager
    let serverURL: URL
    @State private var agreedToPrivacy = false
    @State private var agreedToTerms = false
    @State private var isSubmitting = false

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

                Image(systemName: "doc.text")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)

                Text("Terms & Privacy")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Please review and accept the following before continuing.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(alignment: .leading, spacing: 16) {
                    if let url = privacyPolicyURL {
                        HStack {
                            Button {
                                agreedToPrivacy.toggle()
                            } label: {
                                Image(systemName: agreedToPrivacy ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(agreedToPrivacy ? .blue : .secondary)
                                    .font(.title3)
                            }
                            .buttonStyle(.plain)

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
                                    .foregroundStyle(agreedToTerms ? .blue : .secondary)
                                    .font(.title3)
                            }
                            .buttonStyle(.plain)

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
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!allAgreed || isSubmitting)
                .padding(.horizontal)

                if let error = authManager.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()

                Button("Sign out") {
                    Task { await authManager.logout() }
                }
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.bottom)
            }
            .padding()
            .navigationTitle("")
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
