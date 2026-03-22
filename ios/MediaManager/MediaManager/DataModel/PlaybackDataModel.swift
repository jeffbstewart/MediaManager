import Foundation
import AVFoundation

@MainActor protocol PlaybackDataModel {
    func streamAsset(transcodeId: TranscodeID) async -> AVURLAsset?
    func playbackProgress(transcodeId: TranscodeID) async -> ApiPlaybackProgress?
    func reportProgress(transcodeId: TranscodeID, position: Double, duration: Double?) async
}
