import SwiftUI

/// Persistent now-playing bar pinned above the tab bar / sidebar
/// whenever audio is in the queue. Renders square artwork + track
/// title/artist + play-pause + close. Tap (anywhere except controls)
/// will open the full-screen Now Playing surface in Phase 3 — for now
/// it's a stub that prints to the log so the wire is there.
///
/// Always-visible while a queue exists (per the audio module
/// design memory). The close button calls `audio.stop()` which
/// clears the queue, hides this bar, and tears down system Now
/// Playing.
struct MiniPlayerBar: View {
    @Environment(AudioPlayerManager.self) private var audio
    /// Phase-1 stub: tap to expand. Wired up in Phase 3 to the
    /// full-screen Now Playing view.
    var onTap: () -> Void = {}

    /// Singleton — set by BookReaderView while it's on screen so
    /// this bar tints itself to match the reader's theme (light /
    /// sepia / dark). nil = no reader open, use default styling.
    private let readerTheme = ReaderThemeBroadcaster.shared

    var body: some View {
        if let track = audio.currentTrack {
            HStack(spacing: 12) {
                CachedImage(ref: .posterThumbnail(titleId: track.titleId), cornerRadius: 4)
                    .frame(width: 40, height: 40)  // square — album art aspect ratio

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        // Radio chip — small SF Symbol next to the
                        // title so the user can tell at a glance the
                        // queue is auto-extending vs. a fixed album
                        // playthrough. Matches Apple Music's "station"
                        // affordance.
                        if audio.isRadio {
                            Image(systemName: "dot.radiowaves.left.and.right")
                                .font(.caption2)
                                .foregroundStyle(.tint)
                                .accessibilityLabel("Radio")
                        }
                        Text(track.title)
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                    }
                    // Subtitle joins artist + album when both are
                    // known; collapses to whichever is populated when
                    // one is blank. Library-shuffle tracks carry both
                    // (server populates them); album-detail-played
                    // tracks carry the album name + the credit
                    // AlbumDetailView captured at queue time.
                    Text(subtitle(for: track))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // Whole row except the buttons taps to expand.
                .contentShape(Rectangle())
                .onTapGesture { onTap() }

                Button {
                    audio.previous()
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.body)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Previous track")
                // Always enabled — within the first 3 seconds of a
                // track Previous restarts; from track 1 it's also a
                // restart. AudioPlayerManager.previous() handles both.

                Button {
                    audio.togglePlayPause()
                } label: {
                    Image(systemName: audio.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(audio.isPlaying ? "Pause" : "Play")

                Button {
                    audio.next()
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.body)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Next track")
                .disabled(audio.currentIndex.map { $0 + 1 >= audio.queue.count } ?? true)

                Button {
                    audio.stop()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close player")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            // When the reader is on screen it publishes its colours
            // via ReaderThemeBroadcaster; tint the bar to match so
            // the dark / sepia modes don't end with a stark default-
            // styled strip below the page. Outside the reader the
            // broadcaster's `current` is nil and we fall back to
            // the system-adaptive .regularMaterial.
            //
            // `ignoresSafeArea(edges: .bottom)` extends only the
            // background fill down through the home-indicator
            // strip — the HStack content stays above it (since the
            // inset itself isn't ignored). Without this the bar
            // ended at the safe-area boundary and the system
            // background showed through below it on devices with a
            // home indicator. The bar reads as a continuous slab
            // from the controls to the screen edge.
            .background {
                if let colors = readerTheme.current {
                    colors.background
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    Rectangle().fill(.regularMaterial)
                        .ignoresSafeArea(edges: .bottom)
                }
            }
            .foregroundStyle(readerTheme.current?.foreground ?? .primary)
            .overlay(alignment: .top) {
                // Thin progress strip across the top — the lazy way
                // to show position without consuming row height. Goes
                // from 0 → duration as the track plays.
                ProgressView(value: audio.duration > 0 ? audio.position / audio.duration : 0)
                    .tint(.accentColor)
                    .scaleEffect(x: 1, y: 0.5, anchor: .top)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
            // Hard cap the OUTERMOST measured height. Placing the
            // frame here (after .background / .overlay / .transition)
            // means none of those wrapping views can re-flow the
            // bar's outer frame during navigation transitions or
            // data-load re-layouts — observed without this cap: the
            // bar growing to roughly half the screen while a
            // destination view fetched its initial data. Using
            // `maxHeight` rather than `height` lets the bar collapse
            // to 0 cleanly when `currentTrack` flips to nil.
            .frame(maxHeight: 52)
        }
    }

    /// "Artist · Album" / "Artist" / "Album" depending on what's
    /// populated. Avoids leaving a stranded "·" or an empty subtitle.
    private func subtitle(for track: QueuedTrack) -> String {
        let parts = [track.artistName, track.albumName].filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }
}
