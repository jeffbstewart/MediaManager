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

    /// Shown when no cover for the album is on the phone: a music note on
    /// a dark square. Replacing the previous cover with this, rather than
    /// removing it, keeps the previous album's cover off the screen.
    static let placeholder: UIImage = {
        let size = CGSize(width: 1024, height: 1024)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor(white: 0.16, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let config = UIImage.SymbolConfiguration(pointSize: 360, weight: .regular)
            if let note = UIImage(systemName: "music.note", withConfiguration: config)?
                .withTintColor(UIColor(white: 0.55, alpha: 1), renderingMode: .alwaysOriginal) {
                note.draw(at: CGPoint(x: (size.width - note.size.width) / 2,
                                      y: (size.height - note.size.height) / 2))
            }
        }
    }()

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
