import Foundation
import AVFoundation
import MediaPlayer
import Observation
import UIKit

private let logger = MMLogger(category: "AudioPlayerManager")

/// One queued track. Carries enough metadata to drive the mini-player,
/// the full Now-Playing view, and the system Now-Playing surfaces
/// (lock screen / Control Center / AirPods double-tap menu / Watch /
/// CarPlay) without re-fetching from the server. The shape is
/// deliberately Codable+Sendable so the future watchOS app can
/// transfer downloaded queues over WatchConnectivity without
/// translation work.
struct QueuedTrack: Identifiable, Hashable, Sendable, Codable {
    let id: Int64                  // track id
    let titleId: Int64             // album id (drives artwork)
    let title: String              // track name
    let albumName: String
    let artistName: String
    let trackNumber: Int32
    let discNumber: Int32
    let durationSeconds: Double?   // nil if server didn't provide
}

/// Background-aware AVQueuePlayer wrapper. Single source of truth for
/// audio playback state in the iOS app — every UI surface (mini-player,
/// full-screen Now Playing, future CarPlay templates, future Watch
/// remote) reads from here, and every command (play / pause / next /
/// prev / seek / stop) routes through here. Same handlers wired to
/// `MPRemoteCommandCenter` so AirPods, lock screen, Control Center
/// and CarPlay all hit the same code path as in-app buttons.
///
/// Phase 1 scope: queue management, playback control, system Now
/// Playing info. Listening-progress reporting, radio session
/// continuation, offline-file resolution, and prompt-to-resume all
/// land in later phases on top of this manager.
@Observable
@MainActor
final class AudioPlayerManager {

    // MARK: - Public state

    /// Current playback queue, in order. A new `play()` call replaces
    /// the queue wholesale; `appendToQueue` extends it.
    private(set) var queue: [QueuedTrack] = []

    /// Index of the track currently playing (or paused). nil when the
    /// queue is empty / cleared.
    private(set) var currentIndex: Int? = nil

    /// True while `AVPlayer.timeControlStatus == .playing`.
    private(set) var isPlaying: Bool = false

    /// Current playback position in seconds. Updated ~4×/second by the
    /// time observer. Reset to 0 on track change.
    private(set) var position: Double = 0

    /// Duration of the current item in seconds. Filled in once
    /// AVPlayerItem reports it; falls back to QueuedTrack.durationSeconds
    /// while loading.
    private(set) var duration: Double = 0

    /// Convenience for views.
    var currentTrack: QueuedTrack? {
        guard let i = currentIndex, queue.indices.contains(i) else { return nil }
        return queue[i]
    }

    /// Source-of-truth for MPNowPlayingInfoCenter. Every writer
    /// mutates this dict then publishes the whole thing in one
    /// assignment so per-tick time updates can't race with the
    /// async artwork load. Earlier code read MPNowPlayingInfoCenter
    /// straight back at each call site, mutated, and wrote — which
    /// races: artwork lands at t=0; handleTimeUpdate's t=−250ms
    /// read holds a no-artwork snapshot, then writes at t=+50ms,
    /// blowing away the artwork that just arrived. Symptom on
    /// CarPlay was the cover never drawing even with a successful
    /// "artwork loaded" log line.
    private var nowPlayingInfo: [String: Any] = [:]

    // MARK: - Radio state

    /// Opaque session id (server-assigned, 4h TTL). nil when not in a
    /// radio session.
    private(set) var radioSessionId: String? = nil
    /// What seeded the current session — drives the chrome label
    /// ("Station from <song>" vs "Station from <album>").
    private(set) var radioSeed: ApiRadioSeed? = nil
    /// True while a radio session is active. UI surfaces (mini-player,
    /// now-playing screen) check this to render the station chip.
    var isRadio: Bool { radioSessionId != nil }

    // MARK: - Private

    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var statusObserver: NSKeyValueObservation?
    private var rateObserver: NSKeyValueObservation?
    private var didFinishObserver: NSObjectProtocol?
    /// Captured on every play() call so the future remote-control
    /// path can ask the manager for an authenticated stream URL
    /// without crossing through the data model on every call.
    private var apiClient: APIClient?
    private var imageProvider: ImageProvider?
    /// Set at boot so loadAndPlayCurrent can prefer downloaded
    /// audio files over the streaming endpoint. nil before configure
    /// — playback gracefully falls through to streaming.
    private var audioCache: AudioCacheManager?

    /// Track id whose progress was most recently enqueued. Drives
    /// the "report a final position when the track changes" hook —
    /// when this differs from `currentTrack?.id` we know we just
    /// swapped and need to emit one last position for the outgoing
    /// track before advancing the marker.
    private var lastReportedTrackId: Int64? = nil
    /// Wall-clock of the most recent progress enqueue. The periodic
    /// observer enqueues at a 10-second cadence; this throttle keeps
    /// the queue from growing under the 4×/s time-observer firing.
    private var lastReportedAt: Date = .distantPast
    /// How often to enqueue progress while a track is playing.
    /// Coalescing is in the queue (latest position per track wins),
    /// but we still avoid waking the actor every 250 ms.
    private static let progressReportInterval: TimeInterval = 10

    /// Per-track skip / completion feedback accumulated since the
    /// last NextRadioBatch RPC. Cleared after each successful fetch.
    private var radioHistory: [MMRadioTrackHistory] = []
    /// Debounce flag — keeps us from firing a second batch fetch
    /// while one is in flight (would otherwise happen on every
    /// next() call once the queue gets short).
    private var radioFetchInFlight: Bool = false
    /// Closures the caller hands us when starting radio. We hold
    /// them for the lifetime of the session so the manager can pull
    /// new batches and end the session without depending on the
    /// data model directly.
    private var radioBatchFetcher: (@Sendable (String, [MMRadioTrackHistory]) async throws -> [QueuedTrack])? = nil
    private var radioSessionEnder: (@Sendable (String) async -> Void)? = nil

    init() {
        // Periodic time observer — drives the position scrubber. ~4×/s
        // is dense enough for a smooth UI without pegging the main
        // queue. Always-active because we want the lock screen scrubber
        // updating in the background too.
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: CMTimeScale(NSEC_PER_SEC)),
            queue: .main
        ) { [weak self] time in
            self?.handleTimeUpdate(time)
        }
        rateObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.refreshIsPlaying() }
        }
        wireRemoteCommands()
    }

    // Intentionally no deinit. The manager is owned by the app's
    // root @State and lives for the process lifetime; observer
    // teardown isn't useful at that boundary, and Swift 6 strict
    // concurrency rejects @MainActor property access from a
    // non-isolated deinit anyway.

    func configure(apiClient: APIClient, imageProvider: ImageProvider, audioCache: AudioCacheManager? = nil) {
        self.apiClient = apiClient
        self.imageProvider = imageProvider
        self.audioCache = audioCache
    }

    // MARK: - Playback control

    /// Replace the queue and start playing from `startIndex`.
    /// If a radio session is active, this is treated as the user
    /// abandoning radio for explicit content and the session is torn
    /// down. Radio entry comes through `startRadio(...)` instead.
    func play(tracks: [QueuedTrack], startingAt startIndex: Int = 0) {
        guard !tracks.isEmpty else { return }
        endRadioSession()
        let safeIndex = max(0, min(startIndex, tracks.count - 1))
        queue = tracks
        currentIndex = safeIndex
        Task { await loadAndPlayCurrent() }
    }

    /// Append tracks to the running queue. If nothing is playing,
    /// behaves like `play(tracks:)`.
    func appendToQueue(_ tracks: [QueuedTrack]) {
        guard !tracks.isEmpty else { return }
        if queue.isEmpty {
            play(tracks: tracks)
            return
        }
        queue.append(contentsOf: tracks)
        // AVQueuePlayer manages its own internal queue; we re-sync it
        // on track-change so the pre-loading benefits apply.
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    func resume() {
        // If we have a queue but no live player item (e.g., manager
        // was just configured), reload the current track first.
        if player.currentItem == nil, currentTrack != nil {
            Task { await loadAndPlayCurrent() }
        } else {
            player.play()
        }
        refreshIsPlaying()
        publishNowPlayingInfo()
    }

    func pause() {
        player.pause()
        refreshIsPlaying()
        publishNowPlayingInfo()
        // Pause is the natural moment to ship a fresh position —
        // user might be backgrounding the app or putting the phone
        // down, and we'd rather not wait for the next periodic tick.
        enqueueCurrentProgress()
    }

    func next() {
        guard let i = currentIndex, i + 1 < queue.count else { return }
        recordRadioFeedback(skipped: true)
        currentIndex = i + 1
        Task { await loadAndPlayCurrent() }
        maybeRequestRadioBatch()
    }

    func previous() {
        // First 3 seconds: prev = restart. After that, prev = previous track.
        if position > 3, currentIndex != nil {
            seek(to: 0)
            return
        }
        guard let i = currentIndex, i > 0 else {
            seek(to: 0)
            return
        }
        // Going *back* in radio still counts as a skip on the track
        // we're leaving behind — the user didn't finish it.
        recordRadioFeedback(skipped: true)
        currentIndex = i - 1
        Task { await loadAndPlayCurrent() }
    }

    func seek(to seconds: Double) {
        let target = CMTime(seconds: max(0, seconds), preferredTimescale: 600)
        player.seek(to: target) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.position = seconds
                self?.publishNowPlayingInfo()
            }
        }
    }

    /// Stop playback, clear the queue, and tear down the Now Playing
    /// surface. Called by the mini-player's close button.
    func stop() {
        // Capture the final position before clearing state — same
        // rationale as loadAndPlayCurrent's prologue, but here we
        // know currentTrack is about to vanish so use the explicit
        // outgoing path with the live trackId.
        enqueueCurrentProgress()
        endRadioSession()
        player.pause()
        player.removeAllItems()
        queue = []
        currentIndex = nil
        position = 0
        duration = 0
        isPlaying = false
        nowPlayingInfo.removeAll()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
    }

    // MARK: - Radio

    /// Begin a radio session. The caller has already issued
    /// StartRadio against the server and converted the initial batch
    /// into QueuedTracks; we just wire up the session state and start
    /// playback. The two closures are how the manager talks back to
    /// the server during the session — pulling more tracks when the
    /// queue runs low, and ending the session when the user moves on
    /// or hits stop. Keeping them as closures (rather than baking in
    /// a data-model dependency) keeps AudioPlayerManager testable in
    /// isolation and side-steps actor-crossing constraints.
    func startRadio(
        seed: ApiRadioSeed,
        sessionId: String,
        initialTracks: [QueuedTrack],
        fetchNextBatch: @escaping @Sendable (String, [MMRadioTrackHistory]) async throws -> [QueuedTrack],
        endSession: @escaping @Sendable (String) async -> Void
    ) {
        guard !initialTracks.isEmpty else { return }
        // If a prior radio session exists, end it server-side first
        // (best-effort). We don't await — the new session is starting
        // now regardless.
        endRadioSession()

        radioSessionId = sessionId
        radioSeed = seed
        radioHistory = []
        radioFetchInFlight = false
        radioBatchFetcher = fetchNextBatch
        radioSessionEnder = endSession

        queue = initialTracks
        currentIndex = 0
        Task { await loadAndPlayCurrent() }
    }

    /// Capture per-track feedback before swapping tracks. Called from
    /// next() / previous() with `skipped: true` and from
    /// handleEndOfTrack() with `skipped: false`. No-op outside a
    /// radio session.
    private func recordRadioFeedback(skipped: Bool) {
        guard isRadio, let track = currentTrack else { return }
        var entry = MMRadioTrackHistory()
        entry.trackID = track.id
        if skipped {
            var offset = MMPlaybackOffset()
            offset.seconds = max(0, position)
            entry.skippedAt = offset
        }
        radioHistory.append(entry)
    }

    /// Pre-fetch the next batch when the queue is running low. Three
    /// remaining tracks gives AVQueuePlayer enough runway to stay
    /// gapless while the RPC is in flight, and the debounce flag
    /// makes sure rapid-skipping doesn't fire stacked requests.
    private func maybeRequestRadioBatch() {
        guard isRadio,
              let sessionId = radioSessionId,
              let fetcher = radioBatchFetcher,
              !radioFetchInFlight,
              let i = currentIndex
        else { return }
        let remaining = queue.count - i  // includes the current track
        guard remaining <= 3 else { return }

        radioFetchInFlight = true
        let history = radioHistory
        Task { [weak self] in
            do {
                let nextTracks = try await fetcher(sessionId, history)
                await MainActor.run { [weak self] in
                    self?.handleRadioBatch(nextTracks, consumedHistory: history)
                }
            } catch {
                logger.warning("nextRadioBatch failed: \(error.localizedDescription)")
                await MainActor.run { [weak self] in
                    // Don't tear down the session on a single failure;
                    // surface state for retry on the next skip / track
                    // end. Server expiry would manifest as a fresh
                    // failure here too — we let the user reseed.
                    self?.radioFetchInFlight = false
                }
            }
        }
    }

    /// Splice newly-arrived radio tracks onto the live queue. Trims
    /// the history we just sent so we don't double-report on the
    /// next batch, and clears the in-flight flag.
    private func handleRadioBatch(_ tracks: [QueuedTrack], consumedHistory: [MMRadioTrackHistory]) {
        radioFetchInFlight = false
        // Drop the prefix we sent. New entries can have accumulated
        // mid-flight (user skipped while the RPC was in flight) and
        // those need to ride on the *next* batch.
        if radioHistory.count >= consumedHistory.count {
            radioHistory.removeFirst(consumedHistory.count)
        } else {
            radioHistory.removeAll()
        }
        guard !tracks.isEmpty else {
            logger.warning("nextRadioBatch returned 0 tracks; session likely starved")
            return
        }
        let wasAtEnd = currentIndex.map { $0 >= queue.count - 1 } ?? false
        queue.append(contentsOf: tracks)
        // If we'd drained the queue to its last item and the player
        // already paused at end-of-queue, advance into the new batch.
        if wasAtEnd, !isPlaying, let i = currentIndex, i + 1 < queue.count {
            currentIndex = i + 1
            Task { await loadAndPlayCurrent() }
        }
    }

    // MARK: - Listening progress

    /// Enqueue an outbound ReportListeningProgress write for the
    /// current track. The queue coalesces by track id; flush is
    /// driven by DownloadManager (online → fire RPC immediately on
    /// flush; offline → entry waits for network restore).
    private func enqueueCurrentProgress() {
        guard let track = currentTrack else { return }
        let pos = position
        let dur = duration > 0 ? duration : nil
        lastReportedTrackId = track.id
        lastReportedAt = Date()
        Task.detached {
            await ListeningProgressQueue.shared.enqueue(
                trackId: track.id,
                position: pos,
                duration: dur)
        }
        // Best-effort online flush so a working network gets the
        // write right away instead of waiting for the next batch.
        Task { [weak self] in
            await self?.attemptImmediateFlush()
        }
    }

    /// Capture a final position for whichever track was playing
    /// before we swap to a new one. Called from next() / previous() /
    /// handleEndOfTrack() / loadAndPlayCurrent's prologue. Skips when
    /// the marker already matches the current track (no swap), or
    /// when there's no current track yet (first play after stop).
    private func enqueueOutgoingProgress() {
        guard let lastId = lastReportedTrackId else { return }
        if let current = currentTrack?.id, current == lastId { return }
        // Only the prior track had a position; we don't have its
        // duration handy any more (we've already swapped state). The
        // server's resume logic uses position-only when duration is
        // absent.
        let pos = position
        Task.detached {
            await ListeningProgressQueue.shared.enqueue(
                trackId: lastId,
                position: pos,
                duration: nil)
        }
        // Same best-effort online flush — a track change is exactly
        // when the user expects "Recently Played" to update.
        Task { [weak self] in
            await self?.attemptImmediateFlush()
        }
        lastReportedTrackId = nil
    }

    /// The DownloadManager owns the network monitor and the
    /// grpcClient handle, so let it drive the actual RPCs. We just
    /// poke its flush method; if the network is gone the queue
    /// entries stay put and the next pass picks them up.
    private var progressFlusher: (@Sendable () async -> Void)? = nil

    func configureProgressFlusher(_ flusher: @escaping @Sendable () async -> Void) {
        progressFlusher = flusher
    }

    private func attemptImmediateFlush() async {
        await progressFlusher?()
    }

    /// Tear down the active radio session in-app and best-effort
    /// notify the server. Idempotent — safe to call from stop() /
    /// play() / startRadio() without checking isRadio first.
    private func endRadioSession() {
        guard let id = radioSessionId else { return }
        let ender = radioSessionEnder
        radioSessionId = nil
        radioSeed = nil
        radioHistory.removeAll()
        radioFetchInFlight = false
        radioBatchFetcher = nil
        radioSessionEnder = nil
        if let ender {
            Task.detached { await ender(id) }
        }
    }

    // MARK: - Internal

    private func loadAndPlayCurrent() async {
        // Capture a final position for whatever was playing before
        // we swap, so "Recently Played" reflects the user's actual
        // listening trail rather than only the last track they let
        // play to completion.
        enqueueOutgoingProgress()
        guard let track = currentTrack, let apiClient else { return }
        // Re-assert the audio session before each play. The session
        // can be deactivated between launch and the first play —
        // an incoming call, Siri activation, or another audio app
        // pre-empting us all leave the session in a state that
        // silently no-ops AVPlayer.play() on device. Simulator is
        // forgiving enough that we never noticed; real devices
        // happily let the player render its UI without ever opening
        // the speaker. Re-activating here is cheap (idempotent if
        // already active) and turns the silent-failure mode into
        // actual playback.
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true)
        } catch {
            logger.warning("AVAudioSession re-activation failed: \(error.localizedDescription)")
        }
        // Prefer the downloaded file when present — silent local
        // playback regardless of network state. The streaming
        // endpoint is the fallback for non-downloaded tracks.
        let asset: AVURLAsset
        if let localURL = audioCache?.localTrackURL(trackId: track.id) {
            asset = AVURLAsset(url: localURL)
        } else {
            guard let (url, headers) = await apiClient.audioURL(for: track.id) else {
                logger.warning("could not build audioURL for trackId=\(track.id)")
                return
            }
            asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        }
        let item = AVPlayerItem(asset: asset)
        position = 0
        duration = track.durationSeconds ?? 0

        // Watch for natural end-of-track to advance the queue.
        if let didFinishObserver { NotificationCenter.default.removeObserver(didFinishObserver) }
        didFinishObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.handleEndOfTrack() }
        }
        // Surface decode / load failures. Without this, a bad
        // local file or unreachable stream just sits in .paused
        // state with no diagnostic. Captures the failed-to-play
        // notification and AVPlayerItem.error in one place.
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item, queue: .main
        ) { note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            logger.warning("AVPlayerItem failed to play to end: \(String(describing: err))")
        }
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemNewErrorLogEntry,
            object: item, queue: .main
        ) { _ in
            if let entry = item.errorLog()?.events.last {
                logger.warning("AVPlayerItem error log: domain=\(entry.errorDomain) code=\(entry.errorStatusCode) comment=\(entry.errorComment ?? "")")
            }
        }

        player.removeAllItems()
        player.insert(item, after: nil)
        player.play()
        refreshIsPlaying()
        // Set the textual metadata BEFORE the async artwork fetch.
        // CarPlay snapshots MPNowPlayingInfoCenter the moment play()
        // hits — putting updateNowPlayingInfo after the await meant
        // the head unit drew an empty title/artist and only caught
        // up on the next track change. Order is now:
        //   1. play() so audio starts.
        //   2. updateNowPlayingInfo() — title / artist / album /
        //      duration land in the info center synchronously.
        //   3. updateNowPlayingArtwork() — async image fetch, fills
        //      in MPMediaItemPropertyArtwork once it resolves.
        //      handleTimeUpdate's per-tick refresh preserves the
        //      artwork field via a read-modify-write merge.
        updateNowPlayingInfo()
        await updateNowPlayingArtwork()
    }

    private func handleEndOfTrack() {
        guard let i = currentIndex else { return }
        // Track-completion is a strong signal — explicitly enqueue
        // the final position (== duration) so the resume-prompt
        // doesn't reopen on a track the user finished.
        enqueueCurrentProgress()
        recordRadioFeedback(skipped: false)
        if i + 1 < queue.count {
            currentIndex = i + 1
            Task { await loadAndPlayCurrent() }
            maybeRequestRadioBatch()
        } else if isRadio {
            // Radio queue ran dry before the next batch arrived. If
            // a fetch is already in flight, handleRadioBatch() will
            // pick up advancement when it lands. If not, kick one
            // now (history accumulator carries the just-finished
            // track).
            player.pause()
            position = duration
            refreshIsPlaying()
            updateNowPlayingInfo()
            maybeRequestRadioBatch()
        } else {
            // End of queue. Leave the last track loaded so the
            // mini-player still shows what just finished, but stop
            // playback so the system Now Playing surface goes
            // .paused (not .playing-but-frozen).
            player.pause()
            position = duration
            refreshIsPlaying()
            updateNowPlayingInfo()
        }
    }

    private func handleTimeUpdate(_ time: CMTime) {
        let seconds = time.seconds
        guard seconds.isFinite else { return }
        position = seconds
        if duration == 0, let item = player.currentItem {
            let d = item.duration.seconds
            if d.isFinite { duration = d }
        }
        // Push elapsed time to MPNowPlayingInfoCenter at 1/s. The
        // theoretical "iOS extrapolates from elapsedTime +
        // playbackRate × wall-clock-delta" works on paper but in
        // practice the lock-screen scrubber stays frozen at the
        // value set on the last real state change. Periodic
        // updates are required. 1/s is a middle ground between
        // the original 4/s (excessive churn, candidate suspect for
        // the iOS 26 missing-transport-icons bug) and "never"
        // (frozen scrubber). The SwiftUI mini-player still gets
        // 4/s `position` ticks via @Observable for in-app smoothness.
        if Date().timeIntervalSince(lastInfoCenterUpdate) >= 1.0 {
            lastInfoCenterUpdate = Date()
            publishNowPlayingInfo()
        }

        // Listening-progress reporting at 10 s — the queue
        // coalesces same-track writes, so the throttle is just to
        // keep us from poking the actor 4 ×/s.
        if isPlaying,
           Date().timeIntervalSince(lastReportedAt) >= Self.progressReportInterval {
            enqueueCurrentProgress()
        }
    }

    /// Throttle marker for handleTimeUpdate's MPNowPlayingInfoCenter
    /// writes — see comment in handleTimeUpdate for the cadence
    /// reasoning.
    private var lastInfoCenterUpdate: Date = .distantPast

    /// Atomic publish of the current dict to MPNowPlayingInfoCenter.
    /// Always go through here so writers can't disagree about what
    /// the system sees.
    private func publishNowPlayingInfo() {
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }

    private func refreshIsPlaying() {
        let playing = player.timeControlStatus == .playing
        if playing != isPlaying { isPlaying = playing }
        MPNowPlayingInfoCenter.default().playbackState = playing ? .playing : .paused
    }

    // MARK: - Now Playing info

    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            nowPlayingInfo.removeAll()
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        nowPlayingInfo[MPMediaItemPropertyTitle] = track.title
        // Empty-string artist/album make CarPlay's Now Playing layout
        // fight itself — the system reserves space for the field and
        // empty strings sit on top of adjacent labels. Removing the
        // key entirely lets CarPlay collapse the row cleanly. iOS
        // lock screen behaves the same way.
        if track.artistName.isEmpty {
            nowPlayingInfo.removeValue(forKey: MPMediaItemPropertyArtist)
        } else {
            nowPlayingInfo[MPMediaItemPropertyArtist] = track.artistName
        }
        if track.albumName.isEmpty {
            nowPlayingInfo.removeValue(forKey: MPMediaItemPropertyAlbumTitle)
        } else {
            nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = track.albumName
        }
        nowPlayingInfo[MPMediaItemPropertyAlbumTrackNumber] = NSNumber(value: track.trackNumber)
        nowPlayingInfo[MPMediaItemPropertyDiscNumber] = NSNumber(value: track.discNumber)
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        publishNowPlayingInfo()
    }

    /// Async image fetch + MPMediaItemArtwork wrap. Album art is
    /// SQUARE — call sites that render album covers in the iOS UI
    /// frame square too; here we just hand the bytes through to the
    /// system, which sizes per surface (lock screen, Watch, CarPlay).
    private func updateNowPlayingArtwork() async {
        guard let track = currentTrack, let imageProvider else { return }
        let ref = MMImageRef.posterFull(titleId: track.titleId)
        guard let image = await imageProvider.image(for: ref) else {
            logger.warning("artwork fetch returned nil for trackId=\(track.id) titleId=\(track.titleId)")
            return
        }
        // Capture the track id at fetch time — by the time the image
        // arrives the user may have skipped to the next track and we
        // don't want to clobber the new track's artwork with the old
        // one's bytes.
        guard currentTrack?.id == track.id else { return }
        logger.info("artwork loaded for trackId=\(track.id) size=\(Int(image.size.width))x\(Int(image.size.height))")
        // Pre-render into a square 1024×1024 canvas. Source covers
        // occasionally come in slightly off-square (e.g. 500×499
        // from CAA / TMDB chroma-rounding); CarPlay (and some real
        // head units) silently reject non-square or sub-1024
        // artwork. Letterboxes onto black so non-square sources
        // don't get stretched.
        let squareSize = CGSize(width: 1024, height: 1024)
        let renderer = UIGraphicsImageRenderer(size: squareSize)
        let squareImage = renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(origin: .zero, size: squareSize))
            let aspect = image.size.width / max(image.size.height, 1)
            let drawRect: CGRect
            if aspect >= 1 {
                let h = squareSize.width / aspect
                drawRect = CGRect(x: 0, y: (squareSize.height - h) / 2,
                                  width: squareSize.width, height: h)
            } else {
                let w = squareSize.height * aspect
                drawRect = CGRect(x: (squareSize.width - w) / 2, y: 0,
                                  width: w, height: squareSize.height)
            }
            image.draw(in: drawRect)
        }
        // The artwork request handler is invoked by MediaPlayer on
        // its own dispatch queue, NOT MainActor. Without `@Sendable`
        // Swift 6 inherits MainActor isolation from the enclosing
        // method and traps with `swift_task_checkIsolated` /
        // `dispatch_assert_queue_fail` (SIGTRAP) the moment the
        // system tries to extract the JPEG. UIImage is Sendable, so
        // capturing it through a non-isolated closure is safe.
        // NSLog (not MMLogger) because the closure is @Sendable and
        // can be called from any thread MediaPlayer chooses.
        let artwork = MPMediaItemArtwork(boundsSize: squareSize) { @Sendable size in
            NSLog("[MediaManager] artwork closure invoked at size=%.0fx%.0f", size.width, size.height)
            return squareImage
        }
        nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        publishNowPlayingInfo()
    }

    // MARK: - Remote commands

    private func wireRemoteCommands() {
        let cc = MPRemoteCommandCenter.shared()

        // Each command needs both a target AND an explicit
        // isEnabled = true. addTarget alone leaves the command in
        // its default-enabled state — but iOS sometimes drops a
        // command back to disabled when MPNowPlayingInfoCenter is
        // cleared (which we do on stop()), and CarPlay then hides
        // the corresponding button on the Now Playing screen even
        // though the target is still wired. The skip-forward /
        // skip-backward variants are mutually exclusive with
        // next/previous on CarPlay's layout — disable skip so the
        // head unit shows the track buttons users actually want for
        // music (skip is for podcasts / audiobooks).

        cc.playCommand.isEnabled = true
        cc.playCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.resume() }
            return .success
        }
        cc.pauseCommand.isEnabled = true
        cc.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.pause() }
            return .success
        }
        cc.togglePlayPauseCommand.isEnabled = true
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.togglePlayPause() }
            return .success
        }
        cc.nextTrackCommand.isEnabled = true
        cc.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.next() }
            return .success
        }
        cc.previousTrackCommand.isEnabled = true
        cc.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.previous() }
            return .success
        }
        cc.changePlaybackPositionCommand.isEnabled = true
        cc.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor [weak self] in self?.seek(to: event.positionTime) }
            return .success
        }
        // Music UX expects next/previous buttons, not skip-forward /
        // skip-backward. Leaving skip enabled tells CarPlay this is a
        // long-form-content app (podcast / audiobook) and it swaps
        // the buttons. Explicitly disable so next/prev are the
        // transport controls that surface.
        cc.skipForwardCommand.isEnabled = false
        cc.skipForwardCommand.preferredIntervals = [15]
        cc.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.seek(to: self.position + 15)
            }
            return .success
        }
        cc.skipBackwardCommand.isEnabled = false
        cc.skipBackwardCommand.preferredIntervals = [15]
        cc.skipBackwardCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.seek(to: max(0, self.position - 15))
            }
            return .success
        }
    }
}
