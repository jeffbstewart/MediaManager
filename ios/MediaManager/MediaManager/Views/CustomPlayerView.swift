import SwiftUI
import AVKit
import MediaPlayer

/// Raw AVPlayerLayer wrapped for SwiftUI — no native controls.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.playerLayer.player = player
    }

    class PlayerUIView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// Fully custom video player with fading controls, subtitle overlay,
/// scrub bar, AirPlay, and episode info display.
struct CustomPlayerView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss

    let transcodeId: Int
    let titleName: String
    let episodeName: String?
    var hasSubtitles: Bool = false
    var nextEpisode: NextEpisode? = nil
    var seasonNumber: Int? = nil
    var episodeNumber: Int? = nil

    @State private var player: AVPlayer?
    @State private var error: String?
    @State private var progressTimer: Timer?
    @State private var endObserver: Any?
    @State private var currentTranscodeId: Int?
    @State private var currentEpisodeName: String?
    @State private var currentNextEpisode: NextEpisode?
    @State private var subtitles = SubtitleController()

    // Controls state
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var isPlaying = false
    @State private var currentTime: Double = 0
    @State private var duration: Double = 0
    @State private var isScrubbing = false
    @State private var scrubTime: Double = 0
    @State private var timeObserver: Any?
    @State private var playbackSpeed: Float = 1.0

    private let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    var body: some View {
        Group {
            if let error {
                VStack(spacing: 16) {
                    ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    Button("Close") { cleanupAndDismiss() }
                        .buttonStyle(.borderedProminent)
                }
            } else if let player {
                ZStack {
                    // Video layer
                    PlayerLayerView(player: player)
                        .ignoresSafeArea()

                    // Tap target for showing/hiding controls
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { toggleControls() }

                    // Subtitle overlay (always visible when enabled, doesn't fade)
                    if subtitles.enabled, let subtitle = subtitles.currentText {
                        VStack {
                            Spacer()
                            Text(subtitle)
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(.white)
                                .shadow(color: .black, radius: 3, x: 0, y: 1)
                                .shadow(color: .black, radius: 3, x: 0, y: -1)
                                .shadow(color: .black, radius: 3, x: 1, y: 0)
                                .shadow(color: .black, radius: 3, x: -1, y: 0)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)
                                .padding(.vertical, 6)
                                .background(.black.opacity(0.6))
                                .clipShape(RoundedRectangle(cornerRadius: 4))
                                .padding(.bottom, 80)
                                .allowsHitTesting(false)
                        }
                    }

                    // Controls overlay (fades in/out)
                    if showControls {
                        controlsOverlay(player: player)
                            .transition(.opacity)
                    }
                }
                .ignoresSafeArea()
                .statusBarHidden(true)
            } else {
                ProgressView("Loading stream...")
            }
        }
        .task {
            await loadStream()
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            cleanup()
        }
    }

    // MARK: - Controls Overlay

    @ViewBuilder
    private func controlsOverlay(player: AVPlayer) -> some View {
        ZStack {
            // Dim background
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack {
                // Top bar
                topBar()

                Spacer()

                // Center controls
                centerControls(player: player)

                Spacer()

                // Bottom bar with scrub
                bottomBar(player: player)
            }
            .padding()
        }
    }

    @ViewBuilder
    private func topBar() -> some View {
        HStack {
            // Back button
            Button { cleanupAndDismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }

            // Title info
            VStack(alignment: .leading, spacing: 2) {
                Text(titleName)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if let epName = currentEpisodeName ?? episodeName {
                    HStack(spacing: 4) {
                        if let s = seasonNumber, let e = episodeNumber {
                            Text("S\(s)E\(e)")
                                .fontWeight(.medium)
                        }
                        Text(epName)
                    }
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
                }
            }

            Spacer()

            // CC toggle
            if subtitles.cueCount > 0 {
                Button { subtitles.enabled.toggle(); resetHideTimer() } label: {
                    Text("CC")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(subtitles.enabled ? .black : .white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(subtitles.enabled ? .yellow : .white.opacity(0.3))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                }
            }

            // Playback speed
            Menu {
                ForEach(speeds, id: \.self) { speed in
                    Button {
                        playbackSpeed = speed
                        player?.rate = speed
                        resetHideTimer()
                    } label: {
                        Label(
                            speed == 1.0 ? "Normal" : "\(speed, specifier: "%.2g")×",
                            systemImage: speed == playbackSpeed ? "checkmark" : ""
                        )
                    }
                }
            } label: {
                Text("\(playbackSpeed, specifier: "%.2g")×")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.white.opacity(0.3))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }

            // AirPlay
            AirPlayButton()
                .frame(width: 44, height: 44)
        }
    }

    @ViewBuilder
    private func centerControls(player: AVPlayer) -> some View {
        HStack(spacing: 48) {
            // Back 10
            Button {
                skip(player: player, seconds: -10)
                resetHideTimer()
            } label: {
                Image(systemName: "gobackward.10")
                    .font(.system(size: 36))
                    .foregroundStyle(.white)
            }

            // Play/Pause
            Button {
                if isPlaying {
                    player.pause()
                } else {
                    player.play()
                    player.rate = playbackSpeed
                }
                isPlaying.toggle()
                resetHideTimer()
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.white)
            }

            // Forward 10
            Button {
                skip(player: player, seconds: 10)
                resetHideTimer()
            } label: {
                Image(systemName: "goforward.10")
                    .font(.system(size: 36))
                    .foregroundStyle(.white)
            }
        }
    }

    @ViewBuilder
    private func bottomBar(player: AVPlayer) -> some View {
        VStack(spacing: 8) {
            // Scrub bar
            Slider(
                value: Binding(
                    get: { isScrubbing ? scrubTime : currentTime },
                    set: { newValue in
                        scrubTime = newValue
                        isScrubbing = true
                        resetHideTimer()
                    }
                ),
                in: 0...max(duration, 1)
            ) { editing in
                if !editing && isScrubbing {
                    player.seek(to: CMTime(seconds: scrubTime, preferredTimescale: 600))
                    isScrubbing = false
                }
            }
            .tint(.white)

            // Time labels
            HStack {
                Text(formatTime(isScrubbing ? scrubTime : currentTime))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text("-" + formatTime(max(0, duration - (isScrubbing ? scrubTime : currentTime))))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
    }

    // MARK: - Controls Visibility

    private func toggleControls() {
        withAnimation(.easeInOut(duration: 0.3)) {
            showControls.toggle()
        }
        if showControls {
            resetHideTimer()
        }
    }

    private func resetHideTimer() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.5)) {
                showControls = false
            }
        }
    }

    // MARK: - Playback

    private func skip(player: AVPlayer, seconds: Double) {
        let target = player.currentTime().seconds + seconds
        player.seek(to: CMTime(seconds: max(0, target), preferredTimescale: 600))
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite && seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%d:%02d", m, s)
    }

    // MARK: - Stream Loading

    private func cleanupAndDismiss() {
        saveProgressOnExit()
        cleanup()
        dismiss()
    }

    private func cleanup() {
        stopProgressReporting()
        hideTask?.cancel()
        hideTask = nil
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        subtitles.stop(player: player)
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

        avPlayer.allowsExternalPlayback = true
        avPlayer.usesExternalPlaybackWhileExternalScreenIsActive = true
        self.player = avPlayer
        isPlaying = true
        avPlayer.play()
        startProgressReporting()
        startTimeObserver(player: avPlayer)
        resetHideTimer()

        // End of playback → auto-advance
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [nextEp] _ in
            Task { @MainActor in
                await advanceToNextEpisode(nextEp)
            }
        }

        // Seek to saved position
        Task {
            let progress: ApiPlaybackProgress? = try? await authManager.apiClient.get(
                "playback/progress/\(tcId)")
            if let position = progress?.positionSeconds, position > 0 {
                await avPlayer.seek(to: CMTime(seconds: position, preferredTimescale: 1))
            }
        }

        // Load subtitles
        NSLog("MMAPP loadStream calling subtitles.load for tcId=%d", tcId)
        await subtitles.load(transcodeId: tcId, apiClient: authManager.apiClient)
        NSLog("MMAPP loadStream calling subtitles.startObserving, cueCount=%d", subtitles.cueCount)
        subtitles.startObserving(player: avPlayer)
    }

    private func startTimeObserver(player: AVPlayer) {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            guard !isScrubbing else { return }
            currentTime = time.seconds.isFinite ? time.seconds : 0
            let dur = player.currentItem?.duration.seconds ?? 0
            duration = dur.isFinite ? dur : 0
            isPlaying = player.timeControlStatus == .playing
        }
    }

    // MARK: - Episode Advance

    @MainActor
    private func advanceToNextEpisode(_ nextEp: NextEpisode?) async {
        guard let nextEp else {
            cleanupAndDismiss()
            return
        }
        saveProgressOnExit()
        cleanup()
        currentTranscodeId = nextEp.transcodeId
        currentEpisodeName = nextEp.episodeName
        currentNextEpisode = nil
        await loadStream()
    }

    // MARK: - Progress

    private var activeTranscodeId: Int {
        currentTranscodeId ?? transcodeId
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
        let dur = player.currentItem?.duration.seconds
        guard position.isFinite && position > 0 else { return }
        let tcId = activeTranscodeId
        Task {
            var body: [String: Any] = ["position": position]
            if let dur, dur.isFinite && dur > 0 {
                body["duration"] = dur
            }
            try? await authManager.apiClient.post("playback/progress/\(tcId)", body: body)
        }
    }

    @MainActor
    private func reportProgress() async {
        guard let player else { return }
        let position = player.currentTime().seconds
        let dur = player.currentItem?.duration.seconds
        guard position.isFinite && position > 0 else { return }
        var body: [String: Any] = ["position": position]
        if let dur, dur.isFinite && dur > 0 {
            body["duration"] = dur
        }
        try? await authManager.apiClient.post("playback/progress/\(activeTranscodeId)", body: body)
    }
}

// MARK: - AirPlay Button

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.tintColor = .white
        picker.activeTintColor = .systemBlue
        picker.prioritizesVideoDevices = true
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
