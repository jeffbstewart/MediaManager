import Foundation
import GRPCCore
import Testing
import MediaManagerCore

@Suite("Account deletion refusals")
struct AccountDeletionErrorTests {

    private func text(_ code: RPCError.Code, _ message: String = "") -> String {
        AccountDeletionError(RPCError(code: code, message: message)).localizedDescription
    }

    @Test func wrongPassword() {
        #expect(text(.unauthenticated, "Invalid current password") == "That password is incorrect.")
    }

    @Test func blankPassword() {
        #expect(text(.invalidArgument, "Current password required") == "Enter your current password.")
    }

    @Test func lockoutDropsTheMachineReadableSuffix() {
        // ProfileGrpcService: lockedOutMessage + " Retry after N seconds".
        let server = "Too many incorrect passwords. Try again in 5 minutes. Retry after 300 seconds"
        #expect(text(.resourceExhausted, server) == "Too many incorrect passwords. Try again in 5 minutes.")
    }

    @Test func lockoutWithNoTextStillReadsAsASentence() {
        #expect(text(.resourceExhausted) == "Too many incorrect passwords. Try again later.")
    }

    @Test func lastAdminShowsTheServersInstruction() {
        let server = "You are the only admin. Make another user an admin first."
        #expect(text(.failedPrecondition, server) == server)
    }

    @Test func olderServerWithoutTheRPC() {
        #expect(text(.unimplemented).contains("can't delete accounts yet"))
    }

    @Test func unreachableServerNeverShowsTransportText() {
        let message = text(.unavailable)
        #expect(!message.contains("RPCError"))
        #expect(message.hasSuffix("."))
    }
}
