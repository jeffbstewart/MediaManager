import Foundation
import Observation
import UIKit
import DeclaredAgeRange
import MediaManagerCore

private let logger = MMLogger(category: "AgeGate")

/// Adults-only gate, shown before anything else on first launch. Asks
/// Apple's Declared Age Range service, once per device, whether the
/// owner is 18 or older, and refuses service to anyone it says is not.
///
/// Defense in depth, not proof of age: declining to share, or a device
/// that can't answer, is let through (and remembered, so the question
/// isn't asked again). The App Store rating and the app's terms are the
/// other layers.
///
/// The answer lives in UserDefaults: it survives signing out and
/// account deletion (neither touches this key) and is forgotten only
/// when the app is deleted, which is what the refusal screen invites a
/// minor to do once they turn 18.
@Observable
@MainActor
final class AgeGate {
    /// Persisted: never rename.
    private static let defaultsKey = "ageGateStatus"

    /// The stored answer, for code outside the SwiftUI tree (CarPlay).
    static var storedStatus: AgeGateStatus? {
        UserDefaults.standard.string(forKey: defaultsKey).flatMap(AgeGateStatus.init(rawValue:))
    }

    /// The App Store screenshot capture and the reader UI tests start
    /// from a fresh install; the gate would stop them on its first
    /// screen. Both are launch arguments, which only a test runner can
    /// pass, unlike the screenshot switch a user can flip on About.
    static let bypassedByTestRunner: Bool = {
        let arguments = ProcessInfo.processInfo.arguments
        return arguments.contains(ScreenshotMode.launchArgument)
            || arguments.contains("-MMReaderTestMode")
    }()

    private(set) var status: AgeGateStatus?
    /// Set when this launch's request failed in a way worth retrying:
    /// the person goes through now and is asked again next launch.
    private(set) var passedThisLaunch = false

    init() {
        status = Self.storedStatus
    }

    /// Show the explanation and ask.
    var needsAsking: Bool {
        !Self.bypassedByTestRunner && !passedThisLaunch && AgeGatePolicy.shouldAsk(stored: status)
    }

    /// Show the refusal screen and nothing else.
    var refusesService: Bool {
        !Self.bypassedByTestRunner && AgeGatePolicy.refusesService(stored: status)
    }

    func record(_ answer: AgeGateAnswer) {
        guard let newStatus = AgeGatePolicy.status(for: answer) else {
            logger.info("record: no usable answer (\(String(describing: answer))); asking again next launch")
            passedThisLaunch = true
            return
        }
        logger.info("record: \(newStatus.rawValue)")
        UserDefaults.standard.set(newStatus.rawValue, forKey: Self.defaultsKey)
        status = newStatus
    }

    // MARK: Asking Apple

    /// Shows Apple's age-range sheet over `presenter` and reduces the
    /// outcome to an `AgeGateAnswer`. Apple's response and error types
    /// aren't Sendable, so they're converted here, off the main actor,
    /// and only the Sendable answer comes back.
    nonisolated static func requestAnswer(presentingFrom presenter: UIViewController) async -> AgeGateAnswer {
        do {
            let response = try await AgeRangeService.shared.requestAgeRange(
                ageGates: AgeGatePolicy.adultAge, in: presenter)
            return answer(from: response)
        } catch {
            return answer(from: error)
        }
    }

    nonisolated static func answer(from response: AgeRangeService.Response) -> AgeGateAnswer {
        switch response {
        case .declinedSharing:
            return .declined
        case .sharing(let range):
            return .shared(lowerBound: range.lowerBound, upperBound: range.upperBound)
        @unknown default:
            return .transientFailure
        }
    }

    nonisolated static func answer(from error: Error) -> AgeGateAnswer {
        guard let error = error as? AgeRangeService.Error else { return .transientFailure }
        if error == .notAvailable { return .notAvailable }
        if error == .invalidRequest { return .transientFailure }
        // The remaining cases exist from iOS 27; the app runs on 26.
        if #available(iOS 27.0, *) {
            if error == .declinedOnboarding { return .declined }
            if error == .invalidAccount { return .notAvailable }
        }
        // .network, and anything newer: ask again next launch.
        return .transientFailure
    }

    #if DEBUG
    /// Debug builds only: `-MMAgeGateSimulate minor|adult|declined|unavailable`
    /// answers in place of Apple's service, to see each screen in the
    /// simulator. Launch arguments of that shape arrive as UserDefaults.
    static var simulatedAnswer: AgeGateAnswer? {
        switch UserDefaults.standard.string(forKey: "MMAgeGateSimulate") {
        case "minor": .shared(lowerBound: nil, upperBound: 17)
        case "adult": .shared(lowerBound: 18, upperBound: nil)
        case "declined": .declined
        case "unavailable": .notAvailable
        default: nil
        }
    }
    #endif
}
