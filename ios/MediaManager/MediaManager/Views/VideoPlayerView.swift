import SwiftUI
import AVKit

struct VideoPlayerView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss

    let transcodeId: Int
    let titleName: String
    let episodeName: String?
    var hasSubtitles: Bool = false

    @State private var player: AVPlayer?
    @State private var error: String?
    @State private var progressTimer: Timer?

    var body: some View {
        Group {
            if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
            } else if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            } else {
                ProgressView("Loading stream...")
            }
        }
        .navigationBarHidden(true)
        .task {
            await loadStream()
        }
        .onDisappear {
            stopProgressReporting()
            saveProgressOnExit()
            player?.pause()
            player = nil
        }
    }

    private func loadStream() async {
        guard let (url, headers) = await authManager.apiClient.streamURL(for: transcodeId) else {
            error = "Unable to build stream URL"
            return
        }

        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": headers
        ])
        let item = AVPlayerItem(asset: asset)
        let avPlayer = AVPlayer(playerItem: item)
        avPlayer.automaticallyWaitsToMinimizeStalling = false

        self.player = avPlayer
        avPlayer.play()
        startProgressReporting()

        // Seek to saved position in parallel — don't block playback start
        Task {
            let progress: ApiPlaybackProgress? = try? await authManager.apiClient.get(
                "playback/progress/\(transcodeId)")
            if let position = progress?.positionSeconds, position > 0 {
                await avPlayer.seek(to: CMTime(seconds: position, preferredTimescale: 1))
            }
        }
    }

    @MainActor
    private func startProgressReporting() {
        progressTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { _ in
            Task { @MainActor in
                await reportProgress()
            }
        }
    }

    private func stopProgressReporting() {
        progressTimer?.invalidate()
        progressTimer = nil
    }

    private func saveProgressOnExit() {
        guard let player else { return }
        let position = player.currentTime().seconds
        let duration = player.currentItem?.duration.seconds
        guard position.isFinite && position > 0 else { return }

        Task {
            var body: [String: Any] = ["position": position]
            if let duration, duration.isFinite && duration > 0 {
                body["duration"] = duration
            }
            try? await authManager.apiClient.post("playback/progress/\(transcodeId)", body: body)
        }
    }

    @MainActor
    private func reportProgress() async {
        guard let player else { return }
        let position = player.currentTime().seconds
        let duration = player.currentItem?.duration.seconds
        guard position.isFinite && position > 0 else { return }

        var body: [String: Any] = ["position": position]
        if let duration, duration.isFinite && duration > 0 {
            body["duration"] = duration
        }
        try? await authManager.apiClient.post("playback/progress/\(transcodeId)", body: body)
    }
}
