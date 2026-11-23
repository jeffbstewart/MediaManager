import Foundation
import Testing
import MediaManagerCore

@Suite("CarPlay access")
@MainActor
struct CarPlayAccessTests {

    let server = URL(string: "https://media.example.com")!

    private func access(
        _ state: AuthManager.State, biometric: Bool = false,
        offline: Bool = false, downloads: Bool = true
    ) -> CarPlayAccess {
        CarPlayAccessPolicy.access(
            for: state, awaitingBiometric: biometric, offline: offline, hasDownloadedMusic: downloads)
    }

    @Test func signedInShowsTheServersMusic() {
        #expect(access(.authenticated(serverURL: server)) == .ready(.server))
        #expect(access(.authenticated(serverURL: server), downloads: false) == .ready(.server))
    }

    @Test func restoringWaits() {
        #expect(access(.restoring) == .waiting)
        #expect(access(.restoring, offline: true, downloads: false) == .waiting)
    }

    @Test func signedOutExplains() {
        #expect(access(.needsLogin(serverURL: server)) == .unavailable(.notSignedIn))
        #expect(access(.needsServer) == .unavailable(.notSignedIn))
        #expect(access(.needsSetup(serverURL: server)) == .unavailable(.notSignedIn))
    }

    @Test func waitingForFaceIDCountsAsSignedIn() {
        // The phone has a session and only wants Face ID before showing
        // it; CarPlay can't ask, and has always played in this state.
        #expect(access(.needsLogin(serverURL: server), biometric: true) == .ready(.server))
        #expect(access(.needsLogin(serverURL: server), biometric: true, offline: true) == .ready(.downloads))
    }

    @Test func termsAndServerChangeExplain() {
        #expect(access(.needsLegalAgreement(serverURL: server)) == .unavailable(.termsNotAccepted))
        #expect(access(.fingerprintMismatch(serverURL: server, expected: "a", received: "b"))
                == .unavailable(.serverChanged))
    }

    @Test func offlineShowsDownloadedMusic() {
        #expect(access(.authenticated(serverURL: server), offline: true) == .ready(.downloads))
    }

    @Test func offlineWithNothingDownloadedExplains() {
        #expect(access(.authenticated(serverURL: server), offline: true, downloads: false)
                == .unavailable(.offlineNothingDownloaded))
    }

    @Test func signingInMattersMoreThanBeingOffline() {
        // No music either way; signing in is what to explain.
        #expect(access(.needsLogin(serverURL: server), offline: true, downloads: false) == .unavailable(.notSignedIn))
        #expect(access(.needsLegalAgreement(serverURL: server), offline: true) == .unavailable(.termsNotAccepted))
    }
}
