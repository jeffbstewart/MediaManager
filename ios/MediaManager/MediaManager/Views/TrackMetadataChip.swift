import SwiftUI

/// Inline BPM + time-signature display for tracklist rows. Renders
/// nothing when both are nil — typical for older rips whose tagger
/// didn't write the fields. When one or both are present, formats
/// as compact secondary metadata that fits in the existing row
/// subtitle: "120 BPM · 4/4" / "120 BPM" / "4/4".
///
/// Caller wraps it in whatever Text it likes; this view is purely
/// the chip.
struct TrackMetadataChip: View {
    let bpm: Int?
    let timeSignature: String?

    var body: some View {
        if let formatted {
            Text(formatted)
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    /// Shared formatter so each tracklist site gets identical
    /// punctuation and spacing.
    static func formatted(bpm: Int?, timeSignature: String?) -> String? {
        var parts: [String] = []
        if let bpm, bpm > 0 { parts.append("\(bpm) BPM") }
        if let ts = timeSignature?.trimmingCharacters(in: .whitespaces),
           !ts.isEmpty {
            parts.append(ts)
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var formatted: String? {
        Self.formatted(bpm: bpm, timeSignature: timeSignature)
    }
}
