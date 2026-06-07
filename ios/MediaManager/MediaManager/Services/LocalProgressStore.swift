import Foundation

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
actor LocalProgressStore {
    static let shared = LocalProgressStore()

    // MARK: - Playback (video)

    struct PlaybackEntry: Codable, Sendable {
        var transcodeId: Int64
        var positionSeconds: Double
        var durationSeconds: Double?
        var updatedAt: Date
    }

    // MARK: - Reading (ebook)

    struct ReadingEntry: Codable, Sendable {
        var mediaItemId: Int64
        /// EPUB CFI for `.epub` editions or `/page/N` for `.pdf`.
        /// Opaque to this store — passed through verbatim to the
        /// reader on resume.
        var locator: String
        /// Optional 0..1 fraction for progress-bar display. Reader
        /// always trusts the locator; the fraction is just a UI hint.
        var fraction: Double?
        var updatedAt: Date
    }

    private var playback: [Int64: PlaybackEntry] = [:]
    private var reading: [Int64: ReadingEntry] = [:]
    private let playbackFile: URL
    private let readingFile: URL
    private var loaded = false

    private init() {
        let base: URL
        if let support = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true) {
            base = support
        } else {
            // Documents is gitignored on the device and always writable;
            // fine as the fallback when Application Support isn't.
            base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
        }
        playbackFile = base.appendingPathComponent("playback-progress.json")
        readingFile = base.appendingPathComponent("reading-progress.json")
    }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        if let data = try? Data(contentsOf: playbackFile),
           let decoded = try? JSONDecoder().decode([Int64: PlaybackEntry].self, from: data) {
            playback = decoded
            logger.info("loaded \(decoded.count) playback entries")
        }
        if let data = try? Data(contentsOf: readingFile),
           let decoded = try? JSONDecoder().decode([Int64: ReadingEntry].self, from: data) {
            reading = decoded
            logger.info("loaded \(decoded.count) reading entries")
        }
    }

    private func savePlayback() {
        guard let data = try? JSONEncoder().encode(playback) else { return }
        try? data.write(to: playbackFile, options: .atomic)
    }

    private func saveReading() {
        guard let data = try? JSONEncoder().encode(reading) else { return }
        try? data.write(to: readingFile, options: .atomic)
    }

    // MARK: - Playback API

    func recordPlayback(transcodeId: Int64, position: Double, duration: Double?) {
        loadIfNeeded()
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

    func playback(transcodeId: Int64) -> PlaybackEntry? {
        loadIfNeeded()
        return playback[transcodeId]
    }

    // MARK: - Reading API

    func recordReading(mediaItemId: Int64, locator: String, fraction: Double?) {
        loadIfNeeded()
        guard !locator.isEmpty else { return }
        var entry = reading[mediaItemId]
            ?? ReadingEntry(mediaItemId: mediaItemId, locator: locator, fraction: nil, updatedAt: Date())
        entry.locator = locator
        if let fraction { entry.fraction = fraction }
        entry.updatedAt = Date()
        reading[mediaItemId] = entry
        saveReading()
    }

    func reading(mediaItemId: Int64) -> ReadingEntry? {
        loadIfNeeded()
        return reading[mediaItemId]
    }

    // MARK: - Adapters

    /// Build an `ApiPlaybackProgress` from a local entry. Used by the
    /// data models so callers don't need to know the response came
    /// from local cache vs. the wire.
    func apiPlaybackProgress(transcodeId: Int64) -> ApiPlaybackProgress? {
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

    func apiReadingProgress(mediaItemId: Int64) -> ApiReadingProgress? {
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
