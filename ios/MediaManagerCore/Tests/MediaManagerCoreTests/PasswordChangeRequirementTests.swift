import Testing
import MediaManagerCore

@Suite("Password change requirement")
struct PasswordChangeRequirementTests {
    @Test func toggleOnRequiresAChange() {
        #expect(PasswordChangeRequirement(isRequired: true) == .requiredAtNextSignIn)
        #expect(PasswordChangeRequirement.requiredAtNextSignIn.isRequired)
    }

    @Test func toggleOffKeepsThePassword() {
        #expect(PasswordChangeRequirement(isRequired: false) == .notRequired)
        #expect(!PasswordChangeRequirement.notRequired.isRequired)
    }
}
