import Foundation
import AVFoundation
import Observation
import MediaManagerProtos

/// Manages subtitle loading, parsing, and time-synchronized display.
@Observable
@MainActor
public final class SubtitleController {
    public private(set) var currentText: String?
    public private(set) var cueCount: Int = 0
    public var enabled: Bool = true

    public init() {}

    private var cues: [SubtitleCue] = []
    private var timeObserver: Any?

    public func load(transcodeId: Int, apiClient: APIClient, localDir: URL? = nil, localSubtitleFile: URL? = nil) async {
        // Try local file first (specific path or legacy subs.vtt)
        let localCandidates = [localSubtitleFile, localDir?.appendingPathComponent("subs.vtt")].compactMap { $0 }
        for localURL in localCandidates {
            if let data = try? Data(contentsOf: localURL),
               let content = String(data: data, encoding: .utf8),
               content.contains("-->") {
                let parsed = SubtitleParser.parseVTT(content)
                cues = parsed
                cueCount = parsed.count
                return
            }
        }

        do {
            let data: Data = try await apiClient.getRaw("stream/\(transcodeId)/subs.vtt")
            guard let content = String(data: data, encoding: .utf8) else { return }
            let parsed = SubtitleParser.parseVTT(content)
            cues = parsed
            cueCount = parsed.count
        } catch {
            // No subtitles available
        }
    }

    public func startObserving(player: AVPlayer) {
        guard !cues.isEmpty else {
            return
        }
        let capturedCues = self.cues
        var lastText: String?
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            let seconds = time.seconds
            guard seconds.isFinite else { return }
            let cue = SubtitleParser.activeCue(at: seconds, in: capturedCues)
            let newText = cue?.text
            if newText != lastText {
                lastText = newText
            }
            Task { @MainActor [weak self] in
                self?.currentText = newText
            }
        }
    }

    public func stop(player: AVPlayer?) {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        currentText = nil
        cues = []
        cueCount = 0
    }
}
