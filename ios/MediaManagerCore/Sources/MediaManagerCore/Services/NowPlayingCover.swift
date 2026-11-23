import UIKit

/// Which album cover the Now Playing screen (lock screen, CarPlay,
/// Control Center) shows, decided apart from MediaPlayer so it can be
/// tested.
@MainActor
enum NowPlayingCover {

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
