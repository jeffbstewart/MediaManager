import Foundation
import Testing
import MediaManagerCore

@Suite("CarPlay access")
@MainActor
struct CarPlayAccessTests {

    let server = URL(string: "https://media.example.com")!

    private func access(_ state: AuthManager.State, biometric: Bool = false, offline: Bool = false) -> CarPlayAccess {
        CarPlayAccessPolicy.access(for: state, awaitingBiometric: biometric, offlineMode: offline)
    }

    @Test func signedInShowsMusic() {
        #expect(access(.authenticated(serverURL: server)) == .ready)
    }

    @Test func restoringWaits() {
        #expect(access(.restoring) == .waiting)
        #expect(access(.restoring, offline: true) == .waiting)
    }

    @Test func signedOutExplains() {
        #expect(access(.needsLogin(serverURL: server)) == .unavailable(.notSignedIn))
        #expect(access(.needsServer) == .unavailable(.notSignedIn))
        #expect(access(.needsSetup(serverURL: server)) == .unavailable(.notSignedIn))
    }

    @Test func waitingForFaceIDCountsAsSignedIn() {
        // The phone has a session and only wants Face ID before showing
        // it; CarPlay can't ask, and has always played in this state.
        #expect(access(.needsLogin(serverURL: server), biometric: true) == .ready)
    }

    @Test func termsAndServerChangeExplain() {
        #expect(access(.needsLegalAgreement(serverURL: server)) == .unavailable(.termsNotAccepted))
        #expect(access(.fingerprintMismatch(serverURL: server, expected: "a", received: "b"))
                == .unavailable(.serverChanged))
    }

    @Test func offlineModeExplainsOnceSignedIn() {
        #expect(access(.authenticated(serverURL: server), offline: true) == .unavailable(.offlineMode))
        #expect(access(.needsLogin(serverURL: server), biometric: true, offline: true)
                == .unavailable(.offlineMode))
    }

    @Test func signingInMattersMoreThanOfflineMode() {
        // No music either way; signing in is what to explain.
        #expect(access(.needsLogin(serverURL: server), offline: true) == .unavailable(.notSignedIn))
        #expect(access(.needsLegalAgreement(serverURL: server), offline: true) == .unavailable(.termsNotAccepted))
    }
}
