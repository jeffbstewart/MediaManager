import Foundation
import AVFoundation
import Observation

/// Manages subtitle loading, parsing, and time-synchronized display.
@Observable
@MainActor
final class SubtitleController {
    private(set) var currentText: String?
    private(set) var cueCount: Int = 0
    var enabled: Bool = true

    private var cues: [SubtitleCue] = []
    private var timeObserver: Any?

    func load(transcodeId: Int, apiClient: APIClient, localDir: URL? = nil) async {
        // Try local file first
        if let localDir {
            let localURL = localDir.appendingPathComponent("subs.vtt")
            if let data = try? Data(contentsOf: localURL),
               let content = String(data: data, encoding: .utf8) {
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

    func startObserving(player: AVPlayer) {
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

    func stop(player: AVPlayer?) {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        currentText = nil
        cues = []
        cueCount = 0
    }
}
