import SwiftUI
import AVKit
import Combine

/// Full-screen live stream player for cameras and live TV.
/// Sets a mm_jwt cookie so AVPlayer's native HLS stack authenticates
/// all sub-requests (playlist refreshes, .ts segments).
struct LiveStreamView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    let streamPath: String
    let title: String

    @State private var player: AVPlayer?
    @State private var error: String?
    @State private var buffering = true
    @State private var statusObserver: AnyCancellable?

    var body: some View {
        Group {
            if let error {
                VStack(spacing: 16) {
                    ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    Button("Close") { dismiss() }
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

    private func cleanupAndDismiss() {
        cleanup()
        dismiss()
    }

    private func cleanup() {
        statusObserver?.cancel()
        statusObserver = nil
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

        // Log all player state changes for debugging HLS issues
        statusObserver = item.publisher(for: \.status)
            .combineLatest(
                avPlayer.publisher(for: \.timeControlStatus),
                avPlayer.publisher(for: \.reasonForWaitingToPlay)
            )
            .receive(on: DispatchQueue.main)
            .sink { itemStatus, timeStatus, waitReason in
                let statusStr = switch itemStatus {
                    case .unknown: "unknown"
                    case .readyToPlay: "readyToPlay"
                    case .failed: "failed"
                    @unknown default: "other"
                }
                let timeStr = switch timeStatus {
                    case .paused: "paused"
                    case .playing: "playing"
                    case .waitingToPlayAtSpecifiedRate: "waiting"
                    @unknown default: "other"
                }
                print("[MM-HLS] item.status=\(statusStr) timeControl=\(timeStr) waitReason=\(waitReason?.rawValue ?? "none")")

                if let err = item.error {
                    print("[MM-HLS] item.error: \(err.localizedDescription)")
                    if let underlyingErr = (err as NSError).userInfo[NSUnderlyingErrorKey] as? NSError {
                        print("[MM-HLS] underlying: \(underlyingErr)")
                    }
                }

                if let log = item.errorLog() {
                    for event in log.events {
                        print("[MM-HLS] errorLog: \(event.errorStatusCode) \(event.errorDomain) \(event.errorComment ?? "") uri=\(event.uri ?? "")")
                    }
                }

                if let log = item.accessLog() {
                    for event in log.events {
                        print("[MM-HLS] accessLog: \(event.uri ?? "") bytes=\(event.numberOfBytesTransferred)")
                    }
                }

                if itemStatus == .readyToPlay {
                    avPlayer.play()
                    if timeStatus == .playing {
                        withAnimation { buffering = false }
                    }
                } else if itemStatus == .failed {
                    error = item.error?.localizedDescription ?? "Stream failed"
                }
            }

        self.player = avPlayer
        avPlayer.play()
    }
}
