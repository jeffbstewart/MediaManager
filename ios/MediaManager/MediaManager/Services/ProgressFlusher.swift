import Foundation
import Observation

private let logger = MMLogger(category: "ProgressFlusher")

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
final class ProgressFlusher {
    private let queue: ReadingProgressQueue
    private let downloads: DownloadManager
    private var grpcClient: GrpcClient?
    private var timer: Timer?

    /// 30 s default cadence — see class doc for the rationale.
    private static let flushInterval: TimeInterval = 30

    init(queue: ReadingProgressQueue, downloads: DownloadManager) {
        self.queue = queue
        self.downloads = downloads
    }

    func configure(grpcClient: GrpcClient) {
        self.grpcClient = grpcClient
        startTimer()
        // Kick off an immediate drain — there may be entries left
        // over from a prior session that need flushing.
        Task { await flushNow() }
    }

    /// Drain the queue once, immediately. Called by lifecycle hooks
    /// (book close, app foreground) where waiting for the next timer
    /// tick would leak progress data on a quick subsequent launch.
    func flushNow() async {
        guard !downloads.isEffectivelyOffline else { return }
        guard let grpcClient else { return }

        let entries = await queue.pending()
        if entries.isEmpty { return }
        logger.info("flushing \(entries.count) pending reading-progress entries")

        for entry in entries {
            do {
                try await grpcClient.reportReadingProgress(
                    mediaItemId: entry.mediaItemId,
                    locator: entry.locator,
                    fraction: entry.fraction,
                    clientRecordedAt: entry.recordedAt)
                await queue.markFlushed(
                    mediaItemId: entry.mediaItemId,
                    asOf: entry.recordedAt)
            } catch {
                // Single failure shouldn't abort the rest — partial
                // progress beats none. Next tick re-tries the
                // unflushed entries.
                logger.warning("flush failed for mediaItemId=\(entry.mediaItemId): \(error.localizedDescription)")
            }
        }
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Self.flushInterval, repeats: true) { _ in
            Task { @MainActor in await self.flushNow() }
        }
    }
}
