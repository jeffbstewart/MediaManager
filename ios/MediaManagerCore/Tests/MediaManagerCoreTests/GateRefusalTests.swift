import Foundation
import GRPCCore
import Testing
import MediaManagerCore
import MediaManagerProtos

@Suite("Gate refusals")
struct GateRefusalTests {

    // MARK: Recognising the server's refusals

    @Test func legalDescriptionIsALegalRefusal() {
        let error = RPCError(code: .permissionDenied, message: "legal_agreement_required")
        #expect(GateRefusal(error) == .legalAgreementRequired)
    }

    @Test func passwordDescriptionIsAPasswordRefusal() {
        let error = RPCError(code: .permissionDenied, message: "password_change_required")
        #expect(GateRefusal(error) == .passwordChangeRequired)
    }

    @Test func otherPermissionDeniedIsNotAGateRefusal() {
        #expect(GateRefusal(RPCError(code: .permissionDenied, message: "Admin access required")) == nil)
    }

    @Test func gateDescriptionOnAnotherCodeIsNotAGateRefusal() {
        #expect(GateRefusal(RPCError(code: .invalidArgument, message: "legal_agreement_required")) == nil)
    }

    @Test func nonRPCErrorIsNotAGateRefusal() {
        #expect(GateRefusal(CancellationError()) == nil)
    }

    @Test func wireContractMatchesTheServer() {
        // AuthInterceptor.LEGAL_AGREEMENT_REQUIRED and its password
        // description on the server; renaming either breaks every client.
        #expect(GateRefusal.legalAgreementDescription == "legal_agreement_required")
        #expect(GateRefusal.passwordChangeDescription == "password_change_required")
    }

    @Test func gateRefusalErrorNeverShowsTransportText() {
        for refusal in [GateRefusal.legalAgreementRequired, .passwordChangeRequired] {
            let text = GateRefusalError(refusal).localizedDescription
            #expect(!text.contains("RPCError"))
            #expect(!text.contains("_required"))
        }
    }

    // MARK: Legal refusal: terms screen or stuck message

    private func status(compliant: Bool, privacy: Int32, terms: Int32) -> MMLegalStatusResponse {
        var status = MMLegalStatusResponse()
        status.compliant = compliant
        status.requiredPrivacyPolicyVersion = privacy
        status.requiredTermsOfUseVersion = terms
        return status
    }

    @Test func refusalWithNothingAgreedPresentsTerms() {
        let decision = LegalGateDecision.afterRefusal(
            status: status(compliant: false, privacy: 2, terms: 1), lastAgreed: nil)
        #expect(decision == .presentTerms)
    }

    @Test func newVersionMidSessionPresentsTerms() {
        // Agreed to 1/1 earlier; the admin bumped the privacy policy.
        let decision = LegalGateDecision.afterRefusal(
            status: status(compliant: false, privacy: 2, terms: 1),
            lastAgreed: AgreedLegalVersions(privacyPolicy: 1, termsOfUse: 1))
        #expect(decision == .presentTerms)
    }

    @Test func refusalAfterAgreeingToTheRequiredVersionsIsStuck() {
        // The second refusal after agreeing: showing the terms again
        // would loop, so the user gets the message instead.
        let decision = LegalGateDecision.afterRefusal(
            status: status(compliant: false, privacy: 2, terms: 1),
            lastAgreed: AgreedLegalVersions(privacyPolicy: 2, termsOfUse: 1))
        #expect(decision == .stuck)
    }

    @Test func refusalWhileStatusSaysCompliantIsStuck() {
        // The issue's first flow: the status and the gate disagree.
        let decision = LegalGateDecision.afterRefusal(
            status: status(compliant: true, privacy: 2, terms: 1), lastAgreed: nil)
        #expect(decision == .stuck)
    }

    // MARK: ChangePassword refusals

    @Test func rejectedNewPasswordShowsTheServersRule() {
        let error = PasswordChangeError(RPCError(
            code: .invalidArgument, message: "Password must not contain your username"))
        #expect(error.localizedDescription == "Password must not contain your username")
    }

    @Test func wrongCurrentPasswordShowsTheServersText() {
        let error = PasswordChangeError(RPCError(code: .unauthenticated, message: "Invalid current password"))
        #expect(error.localizedDescription == "Invalid current password")
    }

    @Test func emptyDescriptionsFallBackToASentence() {
        #expect(PasswordChangeError(RPCError(code: .invalidArgument, message: "")).message
            == "The new password was not accepted.")
        #expect(PasswordChangeError(RPCError(code: .unauthenticated, message: "")).message
            == "The current password is incorrect.")
    }

    // MARK: Any other refusal

    @Test(arguments: [
        RPCError.Code.cancelled, .unknown, .invalidArgument, .deadlineExceeded,
        .notFound, .alreadyExists, .permissionDenied, .resourceExhausted,
        .failedPrecondition, .aborted, .outOfRange, .unimplemented,
        .internalError, .unavailable, .dataLoss, .unauthenticated,
    ])
    func everyCodeReadsAsASentence(code: RPCError.Code) {
        let text = RPCError(code: code, message: "").localizedDescription
        #expect(!text.contains("GRPCCore"))
        #expect(!text.contains("RPCError"))
        #expect(text.hasSuffix("."))
    }

    @Test func rejectedValueShowsTheServersDescription() {
        let error = RPCError(code: .invalidArgument, message: "iOS Terms of Use Version must be 1 or more.")
        #expect(error.localizedDescription == "iOS Terms of Use Version must be 1 or more.")
    }

    @Test func internalErrorHidesDeveloperText() {
        let error = RPCError(code: .internalError, message: "NullPointerException at Foo.kt:12")
        #expect(!error.localizedDescription.contains("NullPointerException"))
    }
}
