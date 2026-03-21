import Foundation
import UIKit

struct ThumbnailCue {
    let start: TimeInterval
    let end: TimeInterval
    let sheetIndex: Int
    let x: Int
    let y: Int
    let width: Int
    let height: Int
}

/// Loads thumbnail sprite sheets and provides preview images for scrub positions.
@MainActor
final class ThumbnailScrubber {
    private var cues: [ThumbnailCue] = []
    private var sheets: [Int: UIImage] = [:]
    private var loading = false

    var isAvailable: Bool { !cues.isEmpty }

    /// Load the thumbs.vtt and pre-fetch sprite sheet images.
    func load(transcodeId: Int, apiClient: APIClient) async {
        guard !loading else { return }
        loading = true

        do {
            let data: Data = try await apiClient.getRaw("stream/\(transcodeId)/thumbs.vtt")
            guard let content = String(data: data, encoding: .utf8) else { return }
            cues = parseThumbnailVTT(content)

            // Determine which sprite sheets we need
            let sheetIndices = Set(cues.map { $0.sheetIndex })
            for index in sheetIndices {
                do {
                    let imgData: Data = try await apiClient.getRaw("stream/\(transcodeId)/thumbs_\(index).jpg")
                    if let img = UIImage(data: imgData) {
                        sheets[index] = img
                    }
                } catch {
                }
            }
        } catch {
        }

        loading = false
    }

    /// Get the thumbnail image for a given playback time.
    func thumbnail(at time: TimeInterval) -> UIImage? {
        guard let cue = findCue(at: time) else { return nil }
        guard let sheet = sheets[cue.sheetIndex] else { return nil }
        return cropSprite(from: sheet, cue: cue)
    }

    private func findCue(at time: TimeInterval) -> ThumbnailCue? {
        // Binary search
        var low = 0
        var high = cues.count - 1
        var result: ThumbnailCue?

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

    private func cropSprite(from sheet: UIImage, cue: ThumbnailCue) -> UIImage? {
        let scale = sheet.scale
        let cropRect = CGRect(
            x: CGFloat(cue.x) * scale,
            y: CGFloat(cue.y) * scale,
            width: CGFloat(cue.width) * scale,
            height: CGFloat(cue.height) * scale
        )
        guard let cgImage = sheet.cgImage?.cropping(to: cropRect) else { return nil }
        return UIImage(cgImage: cgImage, scale: scale, orientation: sheet.imageOrientation)
    }

    private func parseThumbnailVTT(_ content: String) -> [ThumbnailCue] {
        var result: [ThumbnailCue] = []
        let normalized = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let blocks = normalized.components(separatedBy: "\n\n")

        for block in blocks {
            let lines = block.components(separatedBy: "\n")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }

            guard let timeLine = lines.first(where: { $0.contains("-->") }) else { continue }
            let parts = timeLine.components(separatedBy: "-->")
            guard parts.count == 2 else { continue }

            guard let start = parseTimestamp(parts[0].trimmingCharacters(in: .whitespaces)),
                  let end = parseTimestamp(parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: " ").first ?? "") else {
                continue
            }

            let timeLineIndex = lines.firstIndex(of: timeLine) ?? 0
            guard let infoLine = lines.dropFirst(timeLineIndex + 1).first else { continue }

            // Parse: thumbs_0.jpg#xywh=0,0,320,180
            guard let sheetMatch = infoLine.range(of: #"thumbs_(\d+)\.jpg"#, options: .regularExpression),
                  let xywhMatch = infoLine.range(of: #"#xywh=(\d+),(\d+),(\d+),(\d+)"#, options: .regularExpression) else {
                continue
            }

            let sheetStr = infoLine[sheetMatch]
            let sheetIndex = Int(sheetStr.replacingOccurrences(of: "thumbs_", with: "").replacingOccurrences(of: ".jpg", with: "")) ?? 0

            let xywhStr = String(infoLine[xywhMatch]).replacingOccurrences(of: "#xywh=", with: "")
            let coords = xywhStr.components(separatedBy: ",").compactMap { Int($0) }
            guard coords.count == 4 else { continue }

            result.append(ThumbnailCue(
                start: start, end: end,
                sheetIndex: sheetIndex,
                x: coords[0], y: coords[1],
                width: coords[2], height: coords[3]
            ))
        }

        return result.sorted { $0.start < $1.start }
    }

    private func parseTimestamp(_ str: String) -> TimeInterval? {
        let clean = str.trimmingCharacters(in: .whitespaces)
        let parts = clean.components(separatedBy: ":")
        switch parts.count {
        case 3:
            guard let h = Double(parts[0]), let m = Double(parts[1]),
                  let s = Double(parts[2].replacingOccurrences(of: ",", with: ".")) else { return nil }
            return h * 3600 + m * 60 + s
        case 2:
            guard let m = Double(parts[0]),
                  let s = Double(parts[1].replacingOccurrences(of: ",", with: ".")) else { return nil }
            return m * 60 + s
        default: return nil
        }
    }
}
