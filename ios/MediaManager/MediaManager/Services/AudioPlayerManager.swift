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

    func configure(apiClient: APIClient, imageProvider: ImageProvider) {
        self.apiClient = apiClient
        self.imageProvider = imageProvider
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
        updateNowPlayingInfo()
    }

    func pause() {
        player.pause()
        refreshIsPlaying()
        updateNowPlayingInfo()
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
                self?.updateNowPlayingInfo()
            }
        }
    }

    /// Stop playback, clear the queue, and tear down the Now Playing
    /// surface. Called by the mini-player's close button.
    func stop() {
        endRadioSession()
        player.pause()
        player.removeAllItems()
        queue = []
        currentIndex = nil
        position = 0
        duration = 0
        isPlaying = false
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
        guard let track = currentTrack, let apiClient else { return }
        guard let (url, headers) = await apiClient.audioURL(for: track.id) else {
            logger.warning("could not build audioURL for trackId=\(track.id)")
            return
        }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
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

        player.removeAllItems()
        player.insert(item, after: nil)
        player.play()
        refreshIsPlaying()
        await updateNowPlayingArtwork()
        updateNowPlayingInfo()
    }

    private func handleEndOfTrack() {
        guard let i = currentIndex else { return }
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
        // Cheap nowPlayingInfo refresh — sets only the elapsed time
        // so the lock-screen scrubber tracks accurately.
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPMediaItemPropertyPlaybackDuration] = duration
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func refreshIsPlaying() {
        let playing = player.timeControlStatus == .playing
        if playing != isPlaying { isPlaying = playing }
        MPNowPlayingInfoCenter.default().playbackState = playing ? .playing : .paused
    }

    // MARK: - Now Playing info

    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPMediaItemPropertyTitle] = track.title
        info[MPMediaItemPropertyArtist] = track.artistName
        info[MPMediaItemPropertyAlbumTitle] = track.albumName
        info[MPMediaItemPropertyAlbumTrackNumber] = NSNumber(value: track.trackNumber)
        info[MPMediaItemPropertyDiscNumber] = NSNumber(value: track.discNumber)
        info[MPMediaItemPropertyPlaybackDuration] = duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Async image fetch + MPMediaItemArtwork wrap. Album art is
    /// SQUARE — call sites that render album covers in the iOS UI
    /// frame square too; here we just hand the bytes through to the
    /// system, which sizes per surface (lock screen, Watch, CarPlay).
    private func updateNowPlayingArtwork() async {
        guard let track = currentTrack, let imageProvider else { return }
        let ref = MMImageRef.posterFull(titleId: track.titleId)
        guard let image = await imageProvider.image(for: ref) else { return }
        // Capture the track id at fetch time — by the time the image
        // arrives the user may have skipped to the next track and we
        // don't want to clobber the new track's artwork with the old
        // one's bytes.
        guard currentTrack?.id == track.id else { return }
        // The artwork request handler is invoked by MediaPlayer on
        // its own dispatch queue, NOT MainActor. Without `@Sendable`
        // Swift 6 inherits MainActor isolation from the enclosing
        // method and traps with `swift_task_checkIsolated` /
        // `dispatch_assert_queue_fail` (SIGTRAP) the moment the
        // system tries to extract the JPEG. UIImage is Sendable, so
        // capturing it through a non-isolated closure is safe.
        let artwork = MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPMediaItemPropertyArtwork] = artwork
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Remote commands

    private func wireRemoteCommands() {
        let cc = MPRemoteCommandCenter.shared()

        cc.playCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.resume() }
            return .success
        }
        cc.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.pause() }
            return .success
        }
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.togglePlayPause() }
            return .success
        }
        cc.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.next() }
            return .success
        }
        cc.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.previous() }
            return .success
        }
        cc.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor [weak self] in self?.seek(to: event.positionTime) }
            return .success
        }
        cc.skipForwardCommand.preferredIntervals = [15]
        cc.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.seek(to: self.position + 15)
            }
            return .success
        }
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
