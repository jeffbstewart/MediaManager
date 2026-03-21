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
    @State private var thumbnails = ThumbnailScrubber()
    @State private var scrubThumbnail: UIImage?
    @State private var skipController = SkipSegmentController()

    // Controls state
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var timeObserver: Any?
    @State private var playbackSpeed: Float = 1.0
    @State private var ps = PlayerState()

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

                    // Skip Intro button
                    if skipController.showSkipIntro {
                        VStack {
                            Spacer()
                            HStack {
                                Spacer()
                                Button {
                                    skipController.skipIntro(player: player)
                                } label: {
                                    Text("Skip Intro")
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 24)
                                        .padding(.vertical, 12)
                                        .background(.white.opacity(0.2))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .stroke(.white.opacity(0.6), lineWidth: 1)
                                        )
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                                .padding(.trailing, 24)
                                .padding(.bottom, 100)
                            }
                        }
                        .transition(.opacity)
                    }

                    if skipController.showUpNext, let nextEp = currentNextEpisode ?? nextEpisode {
                        VStack {
                            Spacer()
                            HStack {
                                Spacer()
                                VStack(spacing: 12) {
                                    Text("Up Next")
                                        .font(.headline)
                                        .foregroundStyle(.white)

                                    Text(nextEp.episodeName)
                                        .font(.subheadline)
                                        .foregroundStyle(.white.opacity(0.8))

                                    if skipController.upNextCountdown > 0 {
                                        Text("Starting in \(skipController.upNextCountdown)s")
                                            .font(.caption)
                                            .foregroundStyle(.white.opacity(0.6))
                                    }

                                    HStack(spacing: 16) {
                                        Button("Play Now") {
                                            skipController.cancelUpNext()
                                            Task { await advanceToNextEpisode(nextEp) }
                                        }
                                        .buttonStyle(.borderedProminent)

                                        Button("Cancel") {
                                            skipController.cancelUpNext()
                                        }
                                        .foregroundStyle(.white)
                                    }
                                }
                                .padding(20)
                                .background(.black.opacity(0.8))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .padding(.trailing, 24)
                                .padding(.bottom, 100)
                            }
                        }
                        .transition(.opacity)
                    }

                    // Controls overlay (fades in/out)
                    if showControls {
                        controlsOverlay(player: player)
                            .transition(.opacity)
                    }
                }
                .ignoresSafeArea()
                .statusBarHidden(true)
                .onChange(of: skipController.upNextCountdown) { _, newValue in
                    if newValue == 0, skipController.showUpNext {
                        let nextEp = currentNextEpisode ?? nextEpisode
                        skipController.cancelUpNext()
                        if let nextEp {
                            Task { await advanceToNextEpisode(nextEp) }
                        }
                    }
                }
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
                if ps.isPlaying {
                    player.pause()
                } else {
                    player.play()
                    player.rate = playbackSpeed
                }
                ps.isPlaying.toggle()
                resetHideTimer()
            } label: {
                Image(systemName: ps.isPlaying ? "pause.fill" : "play.fill")
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
        let displayTime = ps.isScrubbing ? ps.scrubTime : ps.currentTime

        VStack(spacing: 8) {
            // Thumbnail preview during scrubbing
            if ps.isScrubbing, let thumb = ps.scrubThumbnail {
                Image(uiImage: thumb)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(height: 90)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(.white.opacity(0.5), lineWidth: 1)
                    )
                    .shadow(radius: 4)
                    .transition(.opacity)
            }

            // Custom scrub bar using GeometryReader + DragGesture
            // (SwiftUI Slider has re-entrancy bugs with editing callbacks)
            GeometryReader { geo in
                let width = geo.size.width
                let progress = ps.duration > 0 ? displayTime / ps.duration : 0

                ZStack(alignment: .leading) {
                    // Track background
                    Capsule()
                        .fill(.white.opacity(0.3))
                        .frame(height: 4)

                    // Progress fill
                    Capsule()
                        .fill(.white)
                        .frame(width: max(0, width * progress), height: 4)

                    // Thumb
                    Circle()
                        .fill(.white)
                        .frame(width: 14, height: 14)
                        .offset(x: max(0, min(width - 14, width * progress - 7)))
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let fraction = max(0, min(1, value.location.x / width))
                            ps.scrubTime = fraction * ps.duration
                            ps.isScrubbing = true
                            ps.scrubThumbnail = thumbnails.thumbnail(at: ps.scrubTime)
                            resetHideTimer()
                        }
                        .onEnded { _ in
                            player.seek(to: CMTime(seconds: ps.scrubTime, preferredTimescale: 600))
                            ps.isScrubbing = false
                            ps.scrubThumbnail = nil
                        }
                )
            }
            .frame(height: 20)

            // Time labels
            HStack {
                Text(formatTime(displayTime))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text("-" + formatTime(max(0, ps.duration - displayTime)))
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
        skipController.stop(player: player)
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
        ps.isPlaying = true
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

        // Load subtitles, thumbnails, and skip segments in parallel
        async let subLoad: () = subtitles.load(transcodeId: tcId, apiClient: authManager.apiClient)
        async let thumbLoad: () = thumbnails.load(transcodeId: tcId, apiClient: authManager.apiClient)
        async let skipLoad: () = skipController.load(transcodeId: tcId, apiClient: authManager.apiClient)
        _ = await (subLoad, thumbLoad, skipLoad)
        subtitles.startObserving(player: avPlayer)
        skipController.startObserving(player: avPlayer)
    }

    private func startTimeObserver(player: AVPlayer) {
        let state = ps
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            let t = time.seconds
            if t.isFinite { state.currentTime = t }
            let dur = player.currentItem?.duration.seconds ?? 0
            if dur.isFinite && dur > 0 { state.duration = dur }
            state.isPlaying = player.timeControlStatus == .playing
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

/// Reference-type wrapper so AVPlayer closures can read/write current state.
/// @State on a struct creates copies in closures — this class avoids that.
@Observable
@MainActor
final class PlayerState {
    var isPlaying = false
    var currentTime: Double = 0
    var duration: Double = 0
    var isScrubbing = false
    var scrubTime: Double = 0
    var scrubThumbnail: UIImage? = nil
}

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
