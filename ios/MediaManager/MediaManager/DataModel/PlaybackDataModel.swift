import Foundation
import AVFoundation

@MainActor protocol PlaybackDataModel {
    func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset?
    func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress?
    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async

    /// Resume position for an ebook MediaItem, or nil when the user
    /// has never opened the book. Used by `BookReaderView` to call
    /// `rendition.display(resumeCfi)` on first load.
    func readingProgress(mediaItemId: Int64) async -> ApiReadingProgress?

    /// Periodic progress report from the reader. Locator is an EPUB
    /// CFI for `EBOOK_EPUB` editions or `/page/N` for `EBOOK_PDF`;
    /// `fraction` is the 0..1 progress for the progress bar.
    func reportReadingProgress(mediaItemId: Int64, locator: String, fraction: Double?) async
}
