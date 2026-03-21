import SwiftUI
import AVKit
import Combine

/// Full-screen live stream player for cameras and live TV.
/// Sets a mm_jwt cookie so AVPlayer's native HLS stack authenticates
/// all sub-requests (playlist refreshes, .ts segments).
struct LiveStreamView: View {
    @Environment(AuthManager.self) private var authManager
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
                                dismiss()
                            } label: {
                                Image(systemName: "chevron.left.circle.fill")
                                    .font(.title)
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.5))
                            }

                            Text(title)
                                .font(.headline)
                                .foregroundStyle(.white)
                                .shadow(radius: 4)

                            Spacer()
                        }
                        .padding(.top, 8)
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
        .onDisappear {
            statusObserver?.cancel()
            statusObserver = nil
            player?.pause()
            player = nil
        }
    }

    private func loadStream() async {
        guard let baseURL = await authManager.apiClient.getBaseURL(),
              let token = await authManager.apiClient.getAccessToken() else {
            error = "Not authenticated"
            return
        }

        guard let url = URL(string: baseURL.absoluteString + streamPath) else {
            error = "Invalid stream URL"
            return
        }

        // Set mm_jwt cookie so AVPlayer's native HLS handling authenticates
        // all sub-requests (playlist refreshes, .ts segments).
        // AuthFilter checks this cookie and validates the JWT.
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
        let cookie = HTTPCookie(properties: cookieProps)
        if let cookie {
            HTTPCookieStorage.shared.setCookie(cookie)
        }

        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": ["Authorization": "Bearer \(token)"]
        ])
        let item = AVPlayerItem(asset: asset)

        // Live stream tuning
        item.preferredForwardBufferDuration = 2
        item.automaticallyPreservesTimeOffsetFromLive = true

        let avPlayer = AVPlayer(playerItem: item)
        avPlayer.automaticallyWaitsToMinimizeStalling = false

        statusObserver = item.publisher(for: \.status)
            .combineLatest(avPlayer.publisher(for: \.timeControlStatus))
            .receive(on: DispatchQueue.main)
            .sink { itemStatus, timeStatus in
                if itemStatus == .readyToPlay && timeStatus == .playing {
                    withAnimation { buffering = false }
                } else if itemStatus == .failed {
                    error = item.error?.localizedDescription ?? "Stream failed"
                }
            }

        self.player = avPlayer
        avPlayer.play()
    }
}
