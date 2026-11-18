import Foundation
import MediaManagerProtos

private let logger = MMLogger(category: "LocalProgressStore")

/// Per-device local shadow of playback + reading positions. The server
/// remains the cross-device source of truth, but every progress report
/// also writes here so the resume point survives going offline — and
/// so an offline session reading the catalog can still surface the
/// right seek-to position without a round trip. Read order in
/// OnlineDataModel is server-first / local-fallback when online,
/// local-only when offline.
///
/// One file per kind, JSON-encoded `[Int64: Entry]`. The dataset is
/// tiny in practice (a few dozen entries for a heavy user), so we
/// rewrite the entire file on every record() — keeps the
/// load/save logic trivial and the disk footprint small enough not to
/// care about deltas.
///
/// Both files live in the `progress` area of the current storage scope
/// (see `StorageScope`): ids are the server's own, so a position is
/// only meaningful for the server and account that recorded it. With
/// no scope bound, reads return nothing and writes are dropped. A
/// scope change drops what is held in memory and reloads from the new
/// scope's folder. The files from before scopes, at the top of
/// Application Support, are claimed on the first bind.
public actor LocalProgressStore: StorageScopeParticipant {
    public static let shared = LocalProgressStore(
        scope: .shared,
        legacyDirectory: FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first)

    static let playbackFileName = "playback-progress.json"
    static let readingFileName = "reading-progress.json"

    // MARK: - Playback (video)

    public struct PlaybackEntry: Codable, Sendable {
        public var transcodeId: Int64
        public var positionSeconds: Double
        public var durationSeconds: Double?
        public var updatedAt: Date
    }

    // MARK: - Reading (ebook)

    public struct ReadingEntry: Codable, Sendable {
        public var mediaItemId: Int64
        /// EPUB CFI for `.epub` editions or `/page/N` for `.pdf`.
        /// Opaque to this store — passed through verbatim to the
        /// reader on resume.
        public var locator: String
        /// Optional 0..1 fraction for progress-bar display. Reader
        /// always trusts the locator; the fraction is just a UI hint.
        public var fraction: Double?
        public var updatedAt: Date
    }

    private var playback: [Int64: PlaybackEntry] = [:]
    private var reading: [Int64: ReadingEntry] = [:]
    private let scope: StorageScope
    /// Where the files were kept before scopes; claimed on bind.
    private let legacyDirectory: URL?
    private var binding: ScopedFolderBinding = .notYetRead

    /// `scope` supplies the folder; tests pass one over temp folders.
    init(scope: StorageScope, legacyDirectory: URL?) {
        self.scope = scope
        self.legacyDirectory = legacyDirectory
        // Register before the first read of `scope.current`, so no
        // change is missed.
        scope.register(self)
    }

    // MARK: - Scope

    public func storageScopeWillChange() {
        // Every write is saved as it happens; nothing to finish.
        binding = .unbound
        playback.removeAll()
        reading.removeAll()
    }

    public func storageScopeDidChange(to newScope: StorageScopeID?) {
        bind()
    }

    private func bind() {
        playback.removeAll()
        reading.removeAll()
        binding = .unbound
        guard scope.current != nil, let folder = scope.directory(for: .progress) else { return }
        if let legacyDirectory {
            for name in [Self.playbackFileName, Self.readingFileName] {
                scope.claimLegacy(at: legacyDirectory.appendingPathComponent(name),
                                  into: .progress, as: name)
            }
        }
        binding = .bound(folder)
        if let data = try? Data(contentsOf: folder.appendingPathComponent(Self.playbackFileName)),
           let decoded = try? JSONDecoder().decode([Int64: PlaybackEntry].self, from: data) {
            playback = decoded
            logger.info("loaded \(decoded.count) playback entries")
        }
        if let data = try? Data(contentsOf: folder.appendingPathComponent(Self.readingFileName)),
           let decoded = try? JSONDecoder().decode([Int64: ReadingEntry].self, from: data) {
            reading = decoded
            logger.info("loaded \(decoded.count) reading entries")
        }
    }

    /// The bound scope's folder, or nil when nothing is bound. On
    /// launch the scope may already be bound, restored from disk.
    private var folder: URL? {
        if case .notYetRead = binding { bind() }
        return binding.folder
    }

    /// Forget every position and write the empty state to disk. Used
    /// when the account is deleted.
    public func clear() {
        _ = folder
        playback.removeAll()
        reading.removeAll()
        savePlayback()
        saveReading()
    }

    private func savePlayback() {
        guard let folder, let data = try? JSONEncoder().encode(playback) else { return }
        try? data.write(to: folder.appendingPathComponent(Self.playbackFileName), options: .atomic)
    }

    private func saveReading() {
        guard let folder, let data = try? JSONEncoder().encode(reading) else { return }
        try? data.write(to: folder.appendingPathComponent(Self.readingFileName), options: .atomic)
    }

    // MARK: - Playback API

    public func recordPlayback(transcodeId: Int64, position: Double, duration: Double?) {
        guard folder != nil else { return }
        // Skip near-zero updates so a freshly-opened-but-not-watched
        // session doesn't clobber a real prior position. The video
        // player reports position=0 once before the seek lands.
        guard position > 0 else { return }
        var entry = playback[transcodeId]
            ?? PlaybackEntry(transcodeId: transcodeId, positionSeconds: 0, durationSeconds: nil, updatedAt: Date())
        entry.positionSeconds = position
        if let duration { entry.durationSeconds = duration }
        entry.updatedAt = Date()
        playback[transcodeId] = entry
        savePlayback()
    }

    public func playback(transcodeId: Int64) -> PlaybackEntry? {
        guard folder != nil else { return nil }
        return playback[transcodeId]
    }

    // MARK: - Reading API

    public func recordReading(mediaItemId: Int64, locator: String, fraction: Double?) {
        guard folder != nil, !locator.isEmpty else { return }
        var entry = reading[mediaItemId]
            ?? ReadingEntry(mediaItemId: mediaItemId, locator: locator, fraction: nil, updatedAt: Date())
        entry.locator = locator
        if let fraction { entry.fraction = fraction }
        entry.updatedAt = Date()
        reading[mediaItemId] = entry
        saveReading()
    }

    public func reading(mediaItemId: Int64) -> ReadingEntry? {
        guard folder != nil else { return nil }
        return reading[mediaItemId]
    }

    /// All reading entries sorted by `updatedAt` descending — most-
    /// recently-read first. Used by `OfflineDataModel.homeFeed` to
    /// build the offline Continue Reading carousel as an LRU of
    /// downloaded ebooks the user actually has progress in (rather
    /// than the older "Recently Downloaded Books" carousel which
    /// stalls once the user's downloaded a few dozen).
    public func readingEntriesByRecency() -> [ReadingEntry] {
        guard folder != nil else { return [] }
        return reading.values.sorted { $0.updatedAt > $1.updatedAt }
    }

    // MARK: - Adapters

    /// Build an `ApiPlaybackProgress` from a local entry. Used by the
    /// data models so callers don't need to know the response came
    /// from local cache vs. the wire.
    public func apiPlaybackProgress(transcodeId: Int64) -> ApiPlaybackProgress? {
        guard let entry = playback(transcodeId: transcodeId) else { return nil }
        var proto = MMPlaybackProgress()
        proto.transcodeID = entry.transcodeId
        proto.position = MMPlaybackOffset.with { $0.seconds = entry.positionSeconds }
        if let duration = entry.durationSeconds {
            proto.duration = MMPlaybackOffset.with { $0.seconds = duration }
        }
        proto.updatedAt = MMTimestamp.with {
            $0.secondsSinceEpoch = Int64(entry.updatedAt.timeIntervalSince1970)
        }
        return ApiPlaybackProgress(proto: proto)
    }

    public func apiReadingProgress(mediaItemId: Int64) -> ApiReadingProgress? {
        guard let entry = reading(mediaItemId: mediaItemId) else { return nil }
        var proto = MMReadingProgress()
        proto.mediaItemID = entry.mediaItemId
        proto.locator = entry.locator
        if let fraction = entry.fraction { proto.fraction = fraction }
        proto.updatedAt = MMTimestamp.with {
            $0.secondsSinceEpoch = Int64(entry.updatedAt.timeIntervalSince1970)
        }
        return ApiReadingProgress(proto: proto)
    }
}
