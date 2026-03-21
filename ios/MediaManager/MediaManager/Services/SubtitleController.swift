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

    func load(transcodeId: Int, apiClient: APIClient) async {
        NSLog("MMAPP subs loading for transcode %d", transcodeId)
        do {
            let data: Data = try await apiClient.getRaw("stream/\(transcodeId)/subs.vtt")
            NSLog("MMAPP subs received %d bytes", data.count)
            guard let content = String(data: data, encoding: .utf8) else {
                NSLog("MMAPP subs failed UTF-8 decode")
                return
            }
            let lines = content.components(separatedBy: "\n")
            NSLog("MMAPP subs %d lines, first: %@", lines.count, lines.first ?? "empty")
            if lines.count > 1 {
                NSLog("MMAPP subs second line: %@", lines[1])
            }
            if lines.count > 2 {
                NSLog("MMAPP subs third line: %@", lines[2])
            }
            let parsed = SubtitleParser.parseVTT(content)
            NSLog("MMAPP subs parsed %d cues", parsed.count)
            if let first = parsed.first {
                NSLog("MMAPP subs first cue: %.1f-%.1f: %@", first.start, first.end, String(first.text.prefix(60)))
            }
            cues = parsed
            cueCount = parsed.count
        } catch {
            NSLog("MMAPP subs load failed: %@", error.localizedDescription)
        }
    }

    func startObserving(player: AVPlayer) {
        guard !cues.isEmpty else {
            NSLog("MMAPP subs no cues to observe")
            return
        }
        NSLog("MMAPP subs starting observer with %d cues", cues.count)
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
                NSLog("MMAPP subs at %.1fs: %@", seconds, newText ?? "(none)")
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
