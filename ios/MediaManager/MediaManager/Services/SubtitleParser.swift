import Foundation

struct SubtitleCue {
    let start: TimeInterval
    let end: TimeInterval
    let text: String
}

/// Parses WebVTT subtitle files into timed cues.
enum SubtitleParser {

    /// Parse WebVTT content into sorted cues.
    static func parseVTT(_ content: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []
        // Normalize all line endings to \n — handles \r\n (Windows), \r (old Mac), \n (Unix)
        let normalized = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let blocks = normalized.components(separatedBy: "\n\n")

        for block in blocks {
            let lines = block.components(separatedBy: "\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }

            // Find the timestamp line (contains "-->")
            guard let timeLine = lines.first(where: { $0.contains("-->") }) else {
                continue
            }

            let parts = timeLine.components(separatedBy: "-->")
            guard parts.count == 2 else { continue }

            guard let start = parseTimestamp(parts[0].trimmingCharacters(in: .whitespaces)),
                  let end = parseTimestamp(parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: " ").first ?? "") else {
                continue
            }

            // Text lines are everything after the timestamp line
            let timeLineIndex = lines.firstIndex(of: timeLine) ?? 0
            let textLines = lines.dropFirst(timeLineIndex + 1)
            let text = textLines
                .joined(separator: "\n")
                .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression) // strip HTML tags

            if !text.isEmpty {
                cues.append(SubtitleCue(start: start, end: end, text: text))
            }
        }

        return cues.sorted { $0.start < $1.start }
    }

    /// Parse VTT timestamp: "HH:MM:SS.mmm" or "MM:SS.mmm"
    private static func parseTimestamp(_ str: String) -> TimeInterval? {
        let clean = str.trimmingCharacters(in: .whitespaces)
        let parts = clean.components(separatedBy: ":")

        switch parts.count {
        case 3:
            // HH:MM:SS.mmm
            guard let hours = Double(parts[0]),
                  let mins = Double(parts[1]),
                  let secs = Double(parts[2].replacingOccurrences(of: ",", with: ".")) else {
                return nil
            }
            return hours * 3600 + mins * 60 + secs
        case 2:
            // MM:SS.mmm
            guard let mins = Double(parts[0]),
                  let secs = Double(parts[1].replacingOccurrences(of: ",", with: ".")) else {
                return nil
            }
            return mins * 60 + secs
        default:
            return nil
        }
    }

    /// Binary search for the active cue at a given time.
    static func activeCue(at time: TimeInterval, in cues: [SubtitleCue]) -> SubtitleCue? {
        var low = 0
        var high = cues.count - 1
        var result: SubtitleCue?

        while low <= high {
            let mid = (low + high) / 2
            let cue = cues[mid]

            if cue.start <= time {
                if cue.end > time {
                    result = cue
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }
}
