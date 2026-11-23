import Foundation
import Testing
@testable import MediaManagerCore
import MediaManagerProtos

@Suite("Offline album detail")
@MainActor
struct OfflineAlbumDetailTests {

    private func album(trackIds: [Int64]) -> ApiTitleDetail {
        var proto = MMTitleDetail()
        proto.title.id = 7
        proto.title.name = "Album"
        proto.title.mediaType = .album
        proto.album.tracks = trackIds.map { id in
            var track = MMTrack()
            track.id = id
            track.name = "Track \(id)"
            return track
        }
        return ApiTitleDetail(proto: proto)
    }

    @Test func listsOnlyTracksOnThePhone() {
        // A partly downloaded album: tracks 2 and 4 never arrived.
        let detail = OfflineDataModel.keepingDownloadedTracks(album(trackIds: [1, 2, 3, 4]), onDisk: [1, 3])
        #expect(detail.album?.tracks.map(\.id) == [1, 3])
        #expect(detail.name == "Album")
    }

    @Test func aFullDownloadKeepsEveryTrackInOrder() {
        let detail = OfflineDataModel.keepingDownloadedTracks(album(trackIds: [3, 1, 2]), onDisk: [1, 2, 3])
        #expect(detail.album?.tracks.map(\.id) == [3, 1, 2])
    }

    @Test func aTitleWithoutAnAlbumIsUnchanged() {
        var proto = MMTitleDetail()
        proto.title.id = 9
        let detail = OfflineDataModel.keepingDownloadedTracks(ApiTitleDetail(proto: proto), onDisk: [])
        #expect(detail.album == nil)
    }
}
