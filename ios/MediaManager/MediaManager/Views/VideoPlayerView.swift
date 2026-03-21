import SwiftUI
import AVKit

struct VideoPlayerView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss

    let transcodeId: Int
    let titleName: String
    let episodeName: String?
    var hasSubtitles: Bool = false
    var nextEpisode: NextEpisode? = nil

    @State private var player: AVPlayer?
    @State private var error: String?
    @State private var progressTimer: Timer?
    @State private var endObserver: Any?
    @State private var currentTranscodeId: Int?
    @State private var currentEpisodeName: String?
    @State private var currentNextEpisode: NextEpisode?

    var body: some View {
        Group {
            if let error {
                VStack(spacing: 16) {
                    ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    Button("Close") { cleanupAndDismiss() }
                        .buttonStyle(.borderedProminent)
                }
            } else if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
                    .overlay(alignment: .topLeading) {
                        Button {
                            cleanupAndDismiss()
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 28))
                                Text("Close")
                                    .fontWeight(.medium)
                            }
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.6))
                            .clipShape(Capsule())
                        }
                        .padding(.top, 12)
                        .padding(.leading, 16)
                    }
            } else {
                ProgressView("Loading stream...")
            }
        }
        .navigationBarHidden(true)
        .task {
            await loadStream()
        }
        .onDisappear {
            cleanup()
        }
    }

    private func cleanupAndDismiss() {
        saveProgressOnExit()
        cleanup()
        dismiss()
    }

    private func cleanup() {
        stopProgressReporting()
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
    }

    private func loadStream() async {
        let tcId = currentTranscodeId ?? transcodeId
        let nextEp = currentNextEpisode ?? nextEpisode

        guard let (url, headers) = await authManager.apiClient.streamURL(for: tcId) else {
            error = "Unable to build stream URL"
            return
        }

        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": headers
        ])
        let item = AVPlayerItem(asset: asset)
        let avPlayer = AVPlayer(playerItem: item)
        item.preferredForwardBufferDuration = 30

        self.player = avPlayer
        avPlayer.play()
        startProgressReporting()

        // Watch for end of playback → auto-advance to next episode
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [nextEp] _ in
            Task { @MainActor in
                await advanceToNextEpisode(nextEp)
            }
        }

        // Seek to saved position in parallel — don't block playback start
        Task {
            let progress: ApiPlaybackProgress? = try? await authManager.apiClient.get(
                "playback/progress/\(tcId)")
            if let position = progress?.positionSeconds, position > 0 {
                await avPlayer.seek(to: CMTime(seconds: position, preferredTimescale: 1))
            }
        }
    }

    @MainActor
    private func advanceToNextEpisode(_ nextEp: NextEpisode?) async {
        guard let nextEp else {
            cleanupAndDismiss()
            return
        }

        // Save final progress for current episode
        saveProgressOnExit()
        cleanup()

        // Set up next episode
        currentTranscodeId = nextEp.transcodeId
        currentEpisodeName = nextEp.episodeName
        currentNextEpisode = nil // We don't know the next-next episode
        await loadStream()
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

    private var activeTranscodeId: Int {
        currentTranscodeId ?? transcodeId
    }

    private func saveProgressOnExit() {
        guard let player else { return }
        let position = player.currentTime().seconds
        let duration = player.currentItem?.duration.seconds
        guard position.isFinite && position > 0 else { return }
        let tcId = activeTranscodeId

        Task {
            var body: [String: Any] = ["position": position]
            if let duration, duration.isFinite && duration > 0 {
                body["duration"] = duration
            }
            try? await authManager.apiClient.post("playback/progress/\(tcId)", body: body)
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
        try? await authManager.apiClient.post("playback/progress/\(activeTranscodeId)", body: body)
    }
}
