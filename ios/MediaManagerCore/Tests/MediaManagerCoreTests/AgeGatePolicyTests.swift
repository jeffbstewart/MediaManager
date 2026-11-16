import Testing
import MediaManagerCore

@Suite("Age gate policy")
struct AgeGatePolicyTests {

    @Test func underEighteenIsAMinor() {
        // Gate at 18: a minor's range is (nil, 17).
        #expect(AgeGatePolicy.status(for: .shared(lowerBound: nil, upperBound: 17)) == .minor)
        #expect(AgeGatePolicy.status(for: .shared(lowerBound: 13, upperBound: 15)) == .minor)
    }

    @Test func eighteenOrOlderIsAnAdult() {
        // Gate at 18: an adult's range is (18, nil).
        #expect(AgeGatePolicy.status(for: .shared(lowerBound: 18, upperBound: nil)) == .adult)
    }

    @Test func aRangeWithNoAnswerIsUnavailable() {
        #expect(AgeGatePolicy.status(for: .shared(lowerBound: nil, upperBound: nil)) == .unavailable)
    }

    @Test func decliningIsRememberedAndLetThrough() {
        let status = AgeGatePolicy.status(for: .declined)
        #expect(status == .declined)
        #expect(!AgeGatePolicy.refusesService(stored: status))
        #expect(!AgeGatePolicy.shouldAsk(stored: status))
    }

    @Test func aDeviceThatCannotAnswerIsRememberedAndLetThrough() {
        let status = AgeGatePolicy.status(for: .notAvailable)
        #expect(status == .unavailable)
        #expect(!AgeGatePolicy.refusesService(stored: status))
        #expect(!AgeGatePolicy.shouldAsk(stored: status))
    }

    @Test func aTransientFailureStoresNothingSoItAsksAgain() {
        #expect(AgeGatePolicy.status(for: .transientFailure) == nil)
        #expect(AgeGatePolicy.shouldAsk(stored: nil))
        #expect(!AgeGatePolicy.refusesService(stored: nil))
    }

    @Test func onlyAMinorIsRefused() {
        for status in AgeGateStatus.allCases {
            #expect(AgeGatePolicy.refusesService(stored: status) == (status == .minor))
            #expect(!AgeGatePolicy.shouldAsk(stored: status))
        }
    }

    @Test func storedValuesNeverChange() {
        // Persisted in UserDefaults; renaming one would re-ask, or
        // un-refuse, every device.
        #expect(AgeGateStatus.allCases.map(\.rawValue) == ["adult", "declined", "unavailable", "minor"])
    }
}
