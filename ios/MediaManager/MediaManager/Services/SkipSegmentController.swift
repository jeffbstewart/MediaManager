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
    private(set) var chapters: [ChapterData] = []
    private(set) var currentChapterTitle: String?

    private var introSegment: SkipSegmentData?
    private var creditsSegment: SkipSegmentData?
    private var timeObserver: Any?
    private var countdownTimer: Timer?
    private var hasSkippedIntro = false
    private var hasTriggeredUpNext = false

    func load(transcodeId: Int, grpcClient: GrpcClient, localDir: URL? = nil) async {
        // Try local file first
        if let localDir {
            let localFile = localDir.appendingPathComponent("chapters.json")
            if let data = try? Data(contentsOf: localFile),
               let response = try? JSONDecoder().decode(ChaptersResponse.self, from: data) {
                chapters = response.chapters.sorted { $0.number < $1.number }
                for seg in response.skipSegments {
                    if seg.type == "INTRO" { introSegment = seg }
                    if seg.type == "END_CREDITS" { creditsSegment = seg }
                }
                suppressAutoIntroIfChaptered()
                return
            }
        }

        do {
            let response = try await grpcClient.getChapters(transcodeId: Int64(transcodeId))
            chapters = response.chapters.enumerated().map { (index, ch) in
                ChapterData(
                    number: index + 1,
                    start: ch.start.seconds,
                    end: ch.end.seconds,
                    title: ch.title.isEmpty ? nil : ch.title
                )
            }
            for seg in response.skipSegments {
                let type = seg.segmentType
                let data = SkipSegmentData(
                    type: type == .intro ? "INTRO" : type == .credits ? "END_CREDITS" : "UNKNOWN",
                    start: seg.start.seconds,
                    end: seg.end.seconds,
                    method: nil
                )
                if type == .intro { introSegment = data }
                if type == .credits { creditsSegment = data }
            }
            suppressAutoIntroIfChaptered()
        } catch {
            // No chapters/skip data available
        }
    }

    func startObserving(player: AVPlayer) {
        guard introSegment != nil || creditsSegment != nil || !chapters.isEmpty else {
            return
        }

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

        // Current chapter title
        updateCurrentChapter(at: seconds)
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

    /// Seek to a chapter by index.
    func seekToChapter(_ chapter: ChapterData, player: AVPlayer?) {
        player?.seek(to: CMTime(seconds: chapter.start, preferredTimescale: 600))
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
        chapters = []
        currentChapterTitle = nil
    }

    /// If chapters are available, suppress the auto-detected intro skip.
    /// The user can navigate via chapter list instead.
    private func suppressAutoIntroIfChaptered() {
        if !chapters.isEmpty, let intro = introSegment {
            // Only suppress auto-detected (CHAPTER method), not manually created
            if intro.method == "CHAPTER" {
                introSegment = nil
            }
        }
    }

    /// Update current chapter title based on playback position.
    func updateCurrentChapter(at seconds: Double) {
        guard !chapters.isEmpty else { return }
        let current = chapters.last { $0.start <= seconds }
        currentChapterTitle = current?.title
    }
}
