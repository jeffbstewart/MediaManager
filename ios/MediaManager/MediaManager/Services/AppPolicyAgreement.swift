import Foundation
import Observation
import MediaManagerCore
import MediaManagerProtos

private let logger = MMLogger(category: "AppPolicyAgreement")

/// Tracks whether the user has accepted *this app's* Privacy Policy
/// and Terms of Service. Distinct from the server-side legal
/// agreement (which is per-account, signed at the gRPC layer, and
/// covers what the user's chosen server does with their data) —
/// this one is per-device, never transmitted, and covers what the
/// iOS app itself does on the user's phone.
///
/// The split exists because the server is operated by the user
/// (or someone they trust) and its legal terms are between user
/// and server admin; the *app* terms are between the user and the
/// app's distributor (you, on the App Store), which is a separate
/// relationship even when they're the same person.
///
/// Stored in UserDefaults; never shipped to any server. Bumping
/// [currentVersion] re-prompts users on next launch — use that
/// when the privacy policy or ToS substantively change.
@Observable
@MainActor
final class AppPolicyAgreement {

    /// Version stamp. Bump whenever the policy or ToS materially
    /// change so existing installs re-prompt. ISO-date format
    /// keeps the comparison alphabetic-safe.
    static let currentVersion = "2026-05-08"

    /// Public URLs the user can review before tapping Agree. Privacy
    /// is live on TermsFeed; ToS placeholder is replaced when the
    /// authored doc lands. Open via SwiftUI's openURL action.
    static let privacyPolicyURL = URL(string: "https://www.termsfeed.com/live/f2ada3d7-fd0b-415d-94c7-650832091463")!
    static let termsOfServiceURL = URL(string: "https://www.termsfeed.com/live/511d6461-45ee-4d45-8874-9e4935017042")!

    /// MIT license / source repository. Surfaced as a small link on
    /// the agreement screen — partly transparency, partly liability:
    /// the MIT license's "no warranty" clause is reinforced by
    /// making it visible to the user pre-agreement.
    static let sourceCodeURL = URL(string: "https://github.com/jeffbstewart/MediaManager?tab=MIT-1-ov-file")!

    private static let storedVersionKey = "appPolicyAgreementVersion"

    /// True when the locally-stored agreement matches
    /// `currentVersion`. Drives the gate in MediaManagerApp.body.
    private(set) var hasAgreed: Bool

    init() {
        let stored = UserDefaults.standard.string(forKey: Self.storedVersionKey)
        hasAgreed = (stored == Self.currentVersion)
        if let stored, !hasAgreed {
            logger.info("AppPolicyAgreement: stored=\(stored) ≠ current=\(Self.currentVersion); will re-prompt")
        }
    }

    /// User tapped Agree. Persist + flip the flag so the gate
    /// dismisses on the same render pass.
    func accept() {
        UserDefaults.standard.set(Self.currentVersion, forKey: Self.storedVersionKey)
        hasAgreed = true
        logger.info("AppPolicyAgreement: accepted version=\(Self.currentVersion)")
    }

    /// Test / debug entry point — drops the stored agreement so
    /// the next launch re-prompts. Not exposed in production UI.
    func reset() {
        UserDefaults.standard.removeObject(forKey: Self.storedVersionKey)
        hasAgreed = false
    }
}
