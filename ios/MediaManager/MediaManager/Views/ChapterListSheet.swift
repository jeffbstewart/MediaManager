import SwiftUI

/// Shows a list of chapters with timestamps. Tapping a chapter seeks to it.
struct ChapterListSheet: View {
    let chapters: [ChapterData]
    let currentPosition: Double
    let onSelect: (ChapterData) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(chapters.enumerated()), id: \.element.number) { index, chapter in
                    let isCurrent = isCurrentChapter(chapter, index: index)
                    Button {
                        onSelect(chapter)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(chapter.title ?? "Chapter \(chapter.number)")
                                    .font(.subheadline)
                                    .fontWeight(isCurrent ? .bold : .regular)
                                    .foregroundStyle(isCurrent ? .blue : .primary)

                                Text(formatTime(chapter.start))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .monospacedDigit()
                            }

                            Spacer()

                            if isCurrent {
                                Image(systemName: "speaker.wave.2.fill")
                                    .foregroundStyle(.blue)
                                    .font(.caption)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func isCurrentChapter(_ chapter: ChapterData, index: Int) -> Bool {
        guard currentPosition >= chapter.start else { return false }
        if index + 1 < chapters.count {
            return currentPosition < chapters[index + 1].start
        }
        return true // last chapter
    }

    private func formatTime(_ seconds: Double) -> String {
        let h = Int(seconds) / 3600
        let m = (Int(seconds) % 3600) / 60
        let s = Int(seconds) % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%d:%02d", m, s)
    }
}
