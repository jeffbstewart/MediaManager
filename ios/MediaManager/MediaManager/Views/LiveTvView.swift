import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct LiveTvView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var channels: [ApiTvChannel] = []
    @State private var loading = true
    @State private var selectedChannel: ApiTvChannel?
    @Environment(\.marquee) private var layout

    private var columns: [GridItem] {
        layout.wideGridColumns
    }

    private func channelCaption(_ channel: ApiTvChannel) -> String? {
        guard let network = channel.networkAffiliation, !network.isEmpty else { return nil }
        return "\(channel.guideName) • \(network)"
    }

    /// Reception quality indicator: five bars, lit in green.
    private func receptionBars(_ quality: Int) -> some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar <= quality ? MarqueeTheme.Colors.green : MarqueeTheme.Colors.grey3.opacity(0.5))
                    .frame(width: 3, height: CGFloat(4 + bar * 2))
            }
        }
        .accessibilityHidden(true)
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if channels.isEmpty {
                ContentUnavailableView("No channels", systemImage: "tv.slash",
                    description: Text("No live TV channels are available."))
            } else {
                // Channel tiles (spec 3.3): wide cards with the LIVE
                // badge, the channel name over the art and the
                // number as the meta line. The grid wraps to the
                // column width; tapping a tile tunes the channel.
                ScrollView {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: layout.sectionGap) {
                        ForEach(channels) { channel in
                            Button {
                                selectedChannel = channel
                            } label: {
                                VStack(alignment: .leading, spacing: 0) {
                                    MMWideCard(
                                        name: channel.guideName,
                                        ref: nil,
                                        caption: channelCaption(channel),
                                        meta: "Channel \(channel.guideNumber)",
                                        badge: "Live",
                                        badgeTone: .live,
                                        showPlay: true,
                                        width: layout.wideGridCellWidth)
                                    if channel.receptionQuality > 0 {
                                        receptionBars(channel.receptionQuality)
                                            .padding(.top, 6)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Channel \(channel.guideNumber), \(channel.guideName)")
                        }
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                }
            }
        }
        .mmListChrome()
        .navigationTitle("Live TV")
        .task {
            await loadChannels()
        }
        .refreshable {
            await loadChannels()
        }
        .fullScreenCover(item: $selectedChannel) { channel in
            LiveStreamView(
                streamPath: channel.hlsUrl,
                title: "\(channel.guideNumber) \(channel.guideName)",
                // Live TV channels carry their own audio. Pause the
                // music queue while the user is tuned in, matching
                // the way starting a movie hands the audio session
                // off in CustomPlayerView.
                stopsAudio: true,
                onClose: { selectedChannel = nil }
            )
        }
    }

    private func loadChannels() async {
        loading = channels.isEmpty
        do {
            let response = try await dataModel.tvChannels()
            channels = response.channels
        } catch {
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }
}
