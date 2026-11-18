import Foundation
import Observation
import MediaManagerProtos

/// Drains [ReadingProgressQueue] to the server when reachable.
///
/// Lives at app scope; not tied to any single view. Two firing paths:
///
///   - **Periodic** — a 30 s timer attempts to drain whenever the
///     queue is non-empty and the device is online. 30 s is loose
///     enough to not hammer the server when the user is reading
///     online (each `relocated` event already produced an entry that
///     gets sent on the next tick), tight enough that an offline
///     session's worth of progress lands on the server within ~half
///     a minute of reconnection.
///   - **On-demand** — `flushNow()` triggered by lifecycle events
///     where draining-soon matters: app foreground, book close.
///
/// Each entry is sent with its original `recordedAt` timestamp so
/// the server's most-recent-wins resolution makes the right call
/// regardless of how long the entry sat in the queue.
@Observable
@MainActor
public final class ProgressFlusher {
    private let queue: ReadingProgressQueue
    private let downloads: DownloadManager
    private var grpcClient: GrpcClient?
    private var timer: Timer?

    /// 30 s default cadence — see class doc for the rationale.
    private static let flushInterval: TimeInterval = 30

    public init(queue: ReadingProgressQueue, downloads: DownloadManager) {
        self.queue = queue
        self.downloads = downloads
    }

    public func configure(grpcClient: GrpcClient) {
        self.grpcClient = grpcClient
        startTimer()
        // Kick off an immediate drain — there may be entries left
        // over from a prior session that need flushing.
        Task { await flushNow() }
    }

    /// Drain the queue once, immediately. Called by lifecycle hooks
    /// (book close, app foreground) where waiting for the next timer
    /// tick would leak progress data on a quick subsequent launch.
    ///
    /// The queue does the sending (`ReadingProgressQueue.flush`), so a
    /// change of server or account part-way through stops it before
    /// another entry goes out.
    public func flushNow() async {
        guard !downloads.isEffectivelyOffline else { return }
        guard let grpcClient else { return }
        await queue.flush { entry in
            try await grpcClient.reportReadingProgress(
                mediaItemId: entry.mediaItemId,
                locator: entry.locator,
                fraction: entry.fraction,
                clientRecordedAt: entry.recordedAt)
        }
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Self.flushInterval, repeats: true) { _ in
            Task { @MainActor in await self.flushNow() }
        }
    }
}
