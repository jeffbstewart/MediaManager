import Foundation
import Testing
@testable import MediaManagerCore

/// `valueIfReady(of:within:)` is what keeps late cover art from holding up
/// playback (#85).
@Suite("Waiting at most so long")
struct DeadlineTests {

    private final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var _set = false
        var isSet: Bool { lock.withLock { _set } }
        func set() { lock.withLock { _set = true } }
    }

    @Test func aTaskThatFinishesInTimeGivesItsValue() async {
        let task = Task { 42 }
        #expect(await valueIfReady(of: task, within: .seconds(5)) == 42)
    }

    @Test func aSlowTaskGivesNilAtTheDeadlineWithoutWaitingForIt() async throws {
        let clock = ContinuousClock()
        let task = Task { () -> Int in
            try? await Task.sleep(for: .seconds(3))
            return 7
        }
        let started = clock.now
        let value = await valueIfReady(of: task, within: .milliseconds(100))
        let waited = clock.now - started

        #expect(value == nil)
        // Returned at the deadline, well before the task's 3 seconds.
        #expect(waited < .seconds(2))
    }

    @Test func aSlowTaskIsNotCancelledAndStillFinishes() async {
        let finished = Flag()
        let task = Task { () -> Void in
            try? await Task.sleep(for: .milliseconds(300))
            finished.set()
        }
        let value: Void? = await valueIfReady(of: task, within: .milliseconds(20))
        #expect(value == nil)
        #expect(!finished.isSet)

        await task.value
        #expect(finished.isSet)
        #expect(!task.isCancelled)
    }
}
