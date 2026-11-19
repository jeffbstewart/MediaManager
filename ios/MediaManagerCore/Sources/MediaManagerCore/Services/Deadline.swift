import Foundation

/// Waits for `task` for at most `timeout`. Returns its value if it finishes
/// in time, nil otherwise. The task is never cancelled: it carries on and
/// does whatever it does when it finishes, just without the caller waiting.
///
/// Not a task group: a group waits for all of its children before it
/// returns, and `await task.value` does not stop when cancelled, so a group
/// would wait for the slow task after all.
public func valueIfReady<T: Sendable>(of task: Task<T, Never>,
                                      within timeout: Duration) async -> T? {
    await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
        // Installed before either racer starts, so the first to finish
        // always finds it.
        let gate = FirstResultGate(continuation)
        Task { gate.resume(with: await task.value) }
        Task {
            try? await Task.sleep(for: timeout)
            gate.resume(with: nil)
        }
    }
}

/// Resumes its continuation with the first value it is given; later
/// values are ignored.
private final class FirstResultGate<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    /// Guarded by `lock`; nil once resumed.
    private var continuation: CheckedContinuation<T, Never>?

    init(_ continuation: CheckedContinuation<T, Never>) {
        self.continuation = continuation
    }

    func resume(with value: T) {
        let target: CheckedContinuation<T, Never>? = lock.withLock {
            defer { continuation = nil }
            return continuation
        }
        target?.resume(returning: value)
    }
}
