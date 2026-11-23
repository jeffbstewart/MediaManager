import Foundation
import Testing
import MediaManagerCore

@Suite("CarPlay access")
struct CarPlayAccessTests {

    let server = URL(string: "https://media.example.com")!

    @Test func signedInShowsMusic() {
        #expect(CarPlayAccessPolicy.access(for: .authenticated(serverURL: server), awaitingBiometric: false) == .ready)
    }

    @Test func restoringWaits() {
        #expect(CarPlayAccessPolicy.access(for: .restoring, awaitingBiometric: false) == .waiting)
    }

    @Test func signedOutExplains() {
        #expect(CarPlayAccessPolicy.access(for: .needsLogin(serverURL: server), awaitingBiometric: false)
                == .unavailable(.notSignedIn))
        #expect(CarPlayAccessPolicy.access(for: .needsServer, awaitingBiometric: false)
                == .unavailable(.notSignedIn))
        #expect(CarPlayAccessPolicy.access(for: .needsSetup(serverURL: server), awaitingBiometric: false)
                == .unavailable(.notSignedIn))
    }

    @Test func waitingForFaceIDCountsAsSignedIn() {
        // The phone has a session and only wants Face ID before showing
        // it; CarPlay can't ask, and has always played in this state.
        #expect(CarPlayAccessPolicy.access(for: .needsLogin(serverURL: server), awaitingBiometric: true) == .ready)
    }

    @Test func termsAndServerChangeExplain() {
        #expect(CarPlayAccessPolicy.access(for: .needsLegalAgreement(serverURL: server), awaitingBiometric: false)
                == .unavailable(.termsNotAccepted))
        #expect(CarPlayAccessPolicy.access(
            for: .fingerprintMismatch(serverURL: server, expected: "a", received: "b"), awaitingBiometric: false)
                == .unavailable(.serverChanged))
    }

    @Test(arguments: CarPlayUnavailableReason.allCases)
    func messagesWaitUntilTheDriverIsNotDriving(reason: CarPlayUnavailableReason) {
        // CarPlay must never ask a driver to reach for the phone. This
        // checks the English text (the simulator's language); the
        // catalog's translator notes carry the rule to other languages.
        #expect(reason.detail.contains("when you’re not driving"))
        #expect(!reason.title.isEmpty)
    }
}
