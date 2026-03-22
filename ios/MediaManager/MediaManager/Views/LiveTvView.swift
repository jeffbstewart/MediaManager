import SwiftUI

struct LiveTvView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var channels: [ApiTvChannel] = []
    @State private var loading = true
    @State private var selectedChannel: ApiTvChannel?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if channels.isEmpty {
                ContentUnavailableView("No channels", systemImage: "tv.slash",
                    description: Text("No live TV channels are available."))
            } else {
                List(channels) { channel in
                    Button {
                        selectedChannel = channel
                    } label: {
                        HStack(spacing: 12) {
                            Text(channel.guideNumber)
                                .font(.headline)
                                .foregroundStyle(.secondary)
                                .frame(width: 45, alignment: .trailing)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(channel.guideName)
                                    .fontWeight(.medium)
                                    .foregroundStyle(.primary)

                                if let network = channel.networkAffiliation, !network.isEmpty {
                                    Text(network)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            Spacer()

                            // Reception quality indicator
                            HStack(spacing: 2) {
                                ForEach(1...5, id: \.self) { bar in
                                    RoundedRectangle(cornerRadius: 1)
                                        .fill(bar <= channel.receptionQuality ? Color.green : Color.gray.opacity(0.3))
                                        .frame(width: 3, height: CGFloat(4 + bar * 2))
                                }
                            }

                            Image(systemName: "play.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.tint)
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
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
                title: "\(channel.guideNumber) \(channel.guideName)"
            )
        }
    }

    private func loadChannels() async {
        loading = channels.isEmpty
        let response = try? await dataModel.tvChannels()
        channels = response?.channels ?? []
        loading = false
    }
}
