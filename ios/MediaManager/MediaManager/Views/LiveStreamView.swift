import SwiftUI
import AVKit
import Combine
import MediaManagerCore
import MediaManagerProtos

/// Full-screen live stream player for cameras and live TV.
/// Sets a mm_jwt cookie so AVPlayer's native HLS stack authenticates
/// all sub-requests (playlist refreshes, .ts segments).
struct LiveStreamView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(AudioPlayerManager.self) private var audio

    let streamPath: String
    let title: String
    /// True when this stream is expected to carry audio of its own
    /// (Live TV). Pauses the music queue + releases remote-command
    /// claims before tuning in, mirroring CustomPlayerView's
    /// hand-off for movies. Cameras default to false because most
    /// cams are silent and the user is glancing, not watching —
    /// stopping their music there would be a hostile surprise.
    var stopsAudio: Bool = false
    /// Explicit close handler. We don't use `@Environment(\.dismiss)`
    /// here because dismiss() can be flaky on item-based
    /// `fullScreenCover` presentations — observed: taps that did
    /// nothing for several seconds. The parent sets its binding to
    /// nil directly via this closure, which is reliable.
    let onClose: () -> Void

    @State private var player: AVPlayer?
    @State private var error: String?
    @State private var buffering = true
    @State private var observers: Set<AnyCancellable> = []

    var body: some View {
        Group {
            if let error {
                VStack(spacing: 16) {
                    ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    Button("Close") { closeTapped() }
                        .buttonStyle(.borderedProminent)
                }
            } else if let player {
                ZStack {
                    VideoPlayer(player: player)
                        .ignoresSafeArea()

                    if buffering {
                        VStack(spacing: 12) {
                            ProgressView()
                                .scaleEffect(1.5)
                                .tint(.white)
                            Text("Starting stream...")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(.black.opacity(0.6))
                    }

                    VStack {
                        HStack {
                            Button {
                                closeTapped()
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
                                // Pin the hit-test region to the
                                // visible capsule. Without this,
                                // SwiftUI uses the label's rectangular
                                // frame, but capsule corners outside
                                // the rectangle still register taps —
                                // and (per the bug report) the tap
                                // box was reading as "off" near the X
                                // icon. An explicit capsule shape
                                // matches what the user sees.
                                .contentShape(Capsule())
                            }
                            // Custom button style instead of `.plain`
                            // (which strips ALL press feedback) — the
                            // user reported that close-button taps
                            // felt unacknowledged because there was
                            // no visual reaction. A subtle press
                            // animation lands the moment the touch
                            // does, so it's obvious the tap was
                            // received even before the cover starts
                            // dismissing.
                            .buttonStyle(PressFeedbackButtonStyle())

                            Spacer()

                            Text(title)
                                .font(.subheadline)
                                .foregroundStyle(.white)
                                .shadow(radius: 4)
                                .padding(.trailing, 16)
                        }
                        .padding(.top, 12)
                        .padding(.leading, 16)

                        Spacer()
                    }
                }
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("Connecting...")
                        .foregroundStyle(.secondary)
                }
            }
        }
        // Pin the body to fill the available area regardless of
        // which branch (Connecting / video / error) is rendered.
        // Same rationale as ContentView's destination-frame pin: a
        // body whose outer size flips between tiny (the Connecting
        // VStack) and full-screen (the video ZStack) makes the
        // bottom safeAreaInset re-measure during the transition,
        // briefly inflating MiniPlayerBar to ~half the screen.
        // Constant outer size = stable inset = stable bar.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            await loadStream()
        }
        // fullScreenCover lives outside ContentView's NavigationStack,
        // so the app-wide mini-player safeAreaInset doesn't reach
        // here. Re-pin one when the stream isn't taking over the
        // audio session — i.e., on cameras, where the user is
        // glancing at the feed and probably wants to keep their
        // music playable. Hidden on Live TV (`stopsAudio == true`)
        // because tapping play there would put music on top of the
        // broadcast audio.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !stopsAudio {
                MiniPlayerBar()
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            if stopsAudio {
                // Pause whatever's in the queue + release remote-
                // command targets so AirPods / lock-screen controls
                // act on the stream, not the (now-silent) audio
                // player. The queue stays — the user can resume from
                // the mini-player when they close the stream.
                if audio.isPlaying { audio.pause() }
                audio.unwireRemoteCommands()
            }
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            cleanup()
            if stopsAudio {
                audio.wireRemoteCommands()
            }
        }
    }

    /// Close-button action. Cancels the status observer FIRST so the
    /// resulting pause() doesn't trip the sink one more time on its way
    /// out, pauses the player so audio stops immediately, then asks
    /// the parent to clear its binding. The deeper AVPlayer teardown
    /// happens in `onDisappear → cleanup()` after dismiss starts.
    private func closeTapped() {
        // Haptic THUMP the moment the tap registers — no-op on the
        // simulator, but lands on device. Confirms the action even
        // if the press-feedback animation is hard to perceive.
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        observers.removeAll()
        player?.pause()
        onClose()
    }

    private func cleanup() {
        observers.removeAll()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
    }

    private func loadStream() async {
        guard let baseURL = await dataModel.apiClient.getBaseURL(),
              let token = await dataModel.apiClient.getAccessToken() else {
            error = "Not authenticated"
            return
        }

        guard let url = URL(string: baseURL.absoluteString + streamPath) else {
            error = "Invalid stream URL"
            return
        }

        // Warm up the stream before pointing AVPlayer at it.
        // Camera: hit /cam/{id}/start which blocks until relay has segments.
        // Live TV: hit the variant playlist which blocks until FFmpeg produces it.
        let warmupPath: String
        if streamPath.contains("/cam/") {
            warmupPath = streamPath.replacingOccurrences(of: "stream.m3u8", with: "start")
        } else {
            warmupPath = streamPath.replacingOccurrences(of: "stream.m3u8", with: "hls/live.m3u8")
        }
        do {
            try await dataModel.warmUpStream(path: warmupPath)
        } catch {
            self.error = "Stream not available: \(error.localizedDescription)"
            return
        }

        // Use AVURLAssetHTTPCookiesKey (documented public API) — cookies
        // are sent on all HLS sub-requests (playlist refreshes, .ts segments).
        let isSecure = baseURL.scheme == "https"
        var cookieProps: [HTTPCookiePropertyKey: Any] = [
            .name: "mm_jwt",
            .value: token,
            .domain: baseURL.host() ?? "",
            .path: "/",
        ]
        if isSecure {
            cookieProps[.secure] = "TRUE"
        }
        guard let cookie = HTTPCookie(properties: cookieProps) else {
            error = "Failed to create auth cookie"
            return
        }

        let asset = AVURLAsset(url: url, options: [
            AVURLAssetHTTPCookiesKey: [cookie]
        ])
        let item = AVPlayerItem(asset: asset)

        // Live stream tuning
        item.preferredForwardBufferDuration = 2
        item.automaticallyPreservesTimeOffsetFromLive = true

        let avPlayer = AVPlayer(playerItem: item)
        avPlayer.automaticallyWaitsToMinimizeStalling = false

        // Two minimal, deduplicated observers instead of a 3-way
        // `.combineLatest` that fired on every flicker of
        // timeControlStatus / reasonForWaitingToPlay for the
        // duration of the stream. The combo subscription was
        // burning main-thread cycles for the whole session and
        // squeezing out the close-button's press-feedback frames.
        // Now: item.status fires once at .readyToPlay (or on
        // failure), and timeControlStatus fires once when
        // playback actually starts — no continuous wake-up.
        item.publisher(for: \.status)
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { itemStatus in
                if itemStatus == .readyToPlay {
                    avPlayer.play()
                } else if itemStatus == .failed {
                    error = item.error?.localizedDescription ?? "Stream failed"
                }
            }
            .store(in: &observers)

        avPlayer.publisher(for: \.timeControlStatus)
            .filter { $0 == .playing }
            .first()
            .receive(on: DispatchQueue.main)
            .sink { _ in
                withAnimation { buffering = false }
            }
            .store(in: &observers)

        self.player = avPlayer
        avPlayer.play()
    }
}

/// Press feedback — dim + shrink the moment the finger touches.
/// Unlike `.plain` (which strips all feedback) and the system
/// styles (which would add their own tint chrome), this just
/// signals the tap was received. Numbers picked for visibility:
/// 0.4 opacity + 0.88 scale is clearly noticeable in the brief
/// window before the cover starts dismissing.
private struct PressFeedbackButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.4 : 1.0)
            .scaleEffect(configuration.isPressed ? 0.88 : 1.0)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}
