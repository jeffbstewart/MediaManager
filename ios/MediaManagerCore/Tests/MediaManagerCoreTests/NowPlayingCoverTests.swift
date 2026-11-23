import Testing
import UIKit
@testable import MediaManagerCore

@Suite("Now Playing cover")
@MainActor
struct NowPlayingCoverTests {

    private let full = UIImage(systemName: "square.fill")!
    private let thumbnail = UIImage(systemName: "circle.fill")!

    @Test func anotherAlbumDropsTheCover() {
        // The 2026-11-23 drive: offline, one album's cover stayed up
        // through several albums after it.
        #expect(!NowPlayingCover.keepsArtwork(showingTitleId: 1, nextTitleId: 2))
    }

    @Test func theSameAlbumKeepsItsCover() {
        #expect(NowPlayingCover.keepsArtwork(showingTitleId: 1, nextTitleId: 1))
    }

    @Test func noCoverShowingHasNothingToKeep() {
        #expect(!NowPlayingCover.keepsArtwork(showingTitleId: nil, nextTitleId: 1))
    }

    @Test func theFullCoverWins() async {
        let result = await NowPlayingCover.cached(titleId: 7) { ref in
            if ref == .posterFull(titleId: 7) { return full }
            if ref == .posterThumbnail(titleId: 7) { return thumbnail }
            return nil
        }
        #expect(result?.image === full)
        #expect(result?.isFull == true)
    }

    @Test func theThumbnailStandsInForAMissingFullCover() async {
        // What a download had offline: the thumbnail from browsing the
        // lists, but never the full cover.
        let result = await NowPlayingCover.cached(titleId: 7) { ref in
            if ref == .posterThumbnail(titleId: 7) { return thumbnail }
            return nil
        }
        #expect(result?.image === thumbnail)
        #expect(result?.isFull == false)
    }

    @Test func nothingCachedMeansNoCover() async {
        let result = await NowPlayingCover.cached(titleId: 7) { _ in nil }
        #expect(result == nil)
    }

    @Test func itAsksForThisAlbumsCovers() async {
        var asked: [MMImageRef] = []
        _ = await NowPlayingCover.cached(titleId: 42) { ref in
            asked.append(ref)
            return nil
        }
        #expect(asked == [.posterFull(titleId: 42), .posterThumbnail(titleId: 42)])
    }
}
