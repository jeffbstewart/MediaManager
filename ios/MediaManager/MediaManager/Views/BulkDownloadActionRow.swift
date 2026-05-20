import SwiftUI

/// Single row that drives "Download all X" affordances on container
/// views (collections, artist albums, TV seasons, author books,
/// book series). Renders one of three states:
///
///   - idle (pending > 0, inFlight == 0, failed == 0):
///       Tappable "Download all N <noun>s" button.
///   - in flight (inFlight > 0 OR pending > 0 with some completed):
///       Live "Downloading X of N <noun>s" with a determinate
///       progress bar. Disabled — tap is a no-op while the
///       previous batch is still settling.
///   - some failed (failed > 0, no in-flight work):
///       "M <noun>s failed — Retry" affordance that re-fires the
///       same action; DownloadManager.startDownload is idempotent.
///
/// Caller is responsible for hiding the row entirely when there's
/// nothing to do (every item already downloaded). Pass the status
/// computed from the appropriate cache manager
/// (`bulkStatus(forTranscodes:)`, `bulkStatus(forAlbumTitleIds:)`,
/// `bulkStatus(forMediaItems:)`); each yields the same four fields.
struct BulkDownloadActionRow: View {
    let total: Int
    let completed: Int
    let inFlight: Int
    let failed: Int
    /// Lowercase singular noun for the button — "movie", "album",
    /// "episode", "book", "volume". Plural is computed by appending
    /// "s" unless `pluralNoun` is provided (irregulars).
    let noun: String
    let pluralNoun: String?
    let action: () -> Void

    init(
        status: BulkDownloadStatus,
        noun: String,
        pluralNoun: String? = nil,
        action: @escaping () -> Void
    ) {
        self.total = status.total
        self.completed = status.completed
        self.inFlight = status.inFlight
        self.failed = status.failed
        self.noun = noun
        self.pluralNoun = pluralNoun
        self.action = action
    }

    private var pending: Int { max(0, total - completed - inFlight - failed) }
    private var fraction: Double {
        total > 0 ? Double(completed) / Double(total) : 0
    }
    private var plural: String { pluralNoun ?? "\(noun)s" }

    var body: some View {
        if inFlight > 0 {
            // Active state — show progress, don't allow re-fire.
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.down.circle.fill")
                        .foregroundStyle(.tint)
                    Text("Downloading \(completed) of \(total) \(plural)…")
                        .fontWeight(.medium)
                    Spacer()
                }
                ProgressView(value: fraction)
            }
            .padding(12)
            .background(.fill.quaternary)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        } else if failed > 0 && pending == 0 {
            // Settled with failures — offer a single-tap retry.
            Button(action: action) {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.arrow.circlepath")
                    Text("\(failed) \(failed == 1 ? noun : plural) failed — Retry")
                        .fontWeight(.medium)
                    Spacer()
                }
            }
            .buttonStyle(.bordered)
            .tint(.orange)
        } else if pending > 0 {
            // Idle with work to do — the primary call to action.
            let label = pending == total
                ? "Download all \(pending) \(plural)"
                : "Download remaining \(pending) \(plural)"
            Button(action: action) {
                Label(label, systemImage: "arrow.down.circle")
                    .fontWeight(.medium)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        // pending == 0 && failed == 0 && inFlight == 0 → caller is
        // responsible for hiding the row in the "all done" case so
        // the visual checkmark / banner can take over.
    }
}

/// Shared shape for bulk-download progress so one row component
/// works across DownloadManager / AudioCacheManager / BookCacheManager
/// without conditional conformance gymnastics. Each cache manager's
/// own status struct has an `asBulkDownloadStatus` extension below.
struct BulkDownloadStatus: Equatable {
    let total: Int
    let completed: Int
    let inFlight: Int
    let failed: Int
}

extension DownloadManager.BulkStatus {
    var asBulkDownloadStatus: BulkDownloadStatus {
        BulkDownloadStatus(total: total, completed: completed,
                           inFlight: inFlight, failed: failed)
    }
}

extension AudioCacheManager.BulkAlbumStatus {
    var asBulkDownloadStatus: BulkDownloadStatus {
        // Audio cache doesn't track a discrete "failed" state at the
        // album level — a failed track is retried; an album that
        // can't make progress just sits in inFlight. Map accordingly.
        BulkDownloadStatus(total: total, completed: completed,
                           inFlight: inFlight, failed: 0)
    }
}

extension BookCacheManager.BulkBookStatus {
    var asBulkDownloadStatus: BulkDownloadStatus {
        BulkDownloadStatus(total: total, completed: completed,
                           inFlight: inFlight, failed: failed)
    }
}
