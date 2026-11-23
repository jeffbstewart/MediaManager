import MediaPlayer
import UIKit

/// Which album cover the Now Playing screen (lock screen, CarPlay,
/// Control Center) shows, decided apart from MediaPlayer so it can be
/// tested.
@MainActor
enum NowPlayingCover {

    /// `image` as Now Playing artwork, through the closure-based
    /// initializer (`init(image:)` is deprecated). MediaPlayer calls
    /// the closure on a background thread, so it's made here, outside
    /// the main actor: a closure made inside `AudioPlayerManager` would
    /// be bound to the main actor, and calling it off the main thread
    /// fails. That is the likely reason an earlier try left CarPlay's
    /// cover blank. Every size asked for gets the same image; the
    /// system scales it.
    nonisolated static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }

    /// Whether the cover on screen can stay for the next track: only
    /// when it is that track's album. Otherwise it's removed straight
    /// away, so the previous album's cover never shows for this one.
    static func keepsArtwork(showingTitleId: Int64?, nextTitleId: Int64) -> Bool {
        showingTitleId == nextTitleId
    }

    /// The best cover for `titleId` already on the phone: the full
    /// cover if cached, else the thumbnail (what CarPlay's lists show),
    /// else nil. `lookup` must answer from the cache only; it is
    /// `ImageProvider.cachedImage(for:)` in the app.
    static func cached(
        titleId: Int64, lookup: (MMImageRef) async -> UIImage?
    ) async -> (image: UIImage, isFull: Bool)? {
        if let full = await lookup(.posterFull(titleId: titleId)) {
            return (full, true)
        }
        if let thumbnail = await lookup(.posterThumbnail(titleId: titleId)) {
            return (thumbnail, false)
        }
        return nil
    }
}
