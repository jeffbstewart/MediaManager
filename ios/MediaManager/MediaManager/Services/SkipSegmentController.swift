import Foundation
import AVFoundation
import Observation

struct SkipSegmentData: Codable {
    let type: String
    let start: Double
    let end: Double
    let method: String?
}

struct ChaptersResponse: Codable {
    let chapters: [ChapterData]
    let skipSegments: [SkipSegmentData]

    enum CodingKeys: String, CodingKey {
        case chapters
        case skipSegments = "skip_segments"
    }
}

struct ChapterData: Codable {
    let number: Int
    let start: Double
    let end: Double
    let title: String?
}

/// Monitors playback position and triggers skip prompts for intro/credits segments.
@Observable
@MainActor
final class SkipSegmentController {
    private(set) var showSkipIntro = false
    private(set) var showUpNext = false
    private(set) var upNextCountdown: Int = 0

    private var introSegment: SkipSegmentData?
    private var creditsSegment: SkipSegmentData?
    private var timeObserver: Any?
    private var countdownTimer: Timer?
    private var hasSkippedIntro = false
    private var hasTriggeredUpNext = false

    func load(transcodeId: Int, apiClient: APIClient) async {
        NSLog("MMAPP skip loading chapters for transcode %d", transcodeId)
        do {
            let response: ChaptersResponse = try await apiClient.get("stream/\(transcodeId)/chapters")
            NSLog("MMAPP skip got %d chapters, %d skip segments", response.chapters.count, response.skipSegments.count)
            for seg in response.skipSegments {
                NSLog("MMAPP skip segment: type=%@ start=%.1f end=%.1f", seg.type, seg.start, seg.end)
                if seg.type == "INTRO" { introSegment = seg }
                if seg.type == "END_CREDITS" { creditsSegment = seg }
            }
            NSLog("MMAPP skip result: intro=%@, credits=%@",
                  introSegment != nil ? "yes (\(introSegment!.start)-\(introSegment!.end))" : "no",
                  creditsSegment != nil ? "yes (\(creditsSegment!.start)-\(creditsSegment!.end))" : "no")
        } catch {
            NSLog("MMAPP skip load failed: %@", error.localizedDescription)
        }
    }

    func startObserving(player: AVPlayer) {
        guard introSegment != nil || creditsSegment != nil else {
            NSLog("MMAPP skip no segments to observe")
            return
        }
        NSLog("MMAPP skip starting observer")

        let interval = CMTime(seconds: 1.0, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            let seconds = time.seconds
            guard seconds.isFinite else { return }
            Task { @MainActor [weak self] in
                self?.updateState(at: seconds)
            }
        }
    }

    private func updateState(at seconds: Double) {
        // Intro skip
        if let intro = introSegment, !hasSkippedIntro {
            showSkipIntro = seconds >= intro.start && seconds < intro.end
        }

        // Credits / Up Next
        if let credits = creditsSegment, !hasTriggeredUpNext {
            if seconds >= credits.start && seconds < credits.end {
                if !showUpNext {
                    showUpNext = true
                    hasTriggeredUpNext = true
                    startCountdown()
                }
            }
        }
    }

    /// Skip past the intro segment.
    func skipIntro(player: AVPlayer?) {
        guard let intro = introSegment, let player else { return }
        player.seek(to: CMTime(seconds: intro.end, preferredTimescale: 600))
        hasSkippedIntro = true
        showSkipIntro = false
    }

    /// Returns the credits start time for seeking (used by "Up Next" to advance).
    var creditsEndTime: Double? { creditsSegment?.end }

    private func startCountdown() {
        upNextCountdown = 10
        countdownTimer?.invalidate()
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.upNextCountdown > 0 else { return }
                self.upNextCountdown -= 1
            }
        }
    }

    func cancelUpNext() {
        showUpNext = false
        countdownTimer?.invalidate()
        countdownTimer = nil
    }

    func stop(player: AVPlayer?) {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        countdownTimer?.invalidate()
        countdownTimer = nil
        showSkipIntro = false
        showUpNext = false
    }
}
