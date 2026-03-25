import SwiftUI

struct AdminTranscodesView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var transcodes: [AdminLinkedTranscode] = []
    @State private var loading = true
    @State private var page = 1
    @State private var totalPages = 0

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if transcodes.isEmpty {
                ContentUnavailableView("No linked transcodes", systemImage: "film.stack")
            } else {
                List {
                    ForEach(transcodes) { tc in
                        HStack(spacing: 12) {
                            CachedImage(ref: .posterThumbnail(titleId: tc.titleId.protoValue))
                            .frame(width: 40, height: 60)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(tc.titleName)
                                    .fontWeight(.medium)
                                    .lineLimit(1)

                                if let s = tc.seasonNumber, let e = tc.episodeNumber {
                                    Text("S\(s)E\(e)" + (tc.episodeName.map { " \($0)" } ?? ""))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }

                                Text(tc.filePath?.components(separatedBy: "/").last ?? "Unknown file")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)

                                HStack(spacing: 6) {
                                    if let format = tc.mediaFormat {
                                        Text(format)
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                    if tc.retranscodeRequested == true {
                                        Text("Re-transcode requested")
                                            .font(.caption2)
                                            .foregroundStyle(.orange)
                                    }
                                }
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            Button("Unlink", role: .destructive) {
                                Task { await unlinkTranscode(tc) }
                            }
                        }
                        .contextMenu {
                            Button("Unlink Transcode", role: .destructive) {
                                Task { await unlinkTranscode(tc) }
                            }
                        }
                    }

                    if page < totalPages {
                        Button("Load More") {
                            Task { await loadMore() }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .navigationTitle("Linked Transcodes")
        .task { await loadTranscodes() }
        .refreshable {
            page = 1
            await loadTranscodes()
        }
    }

    private func loadTranscodes() async {
        loading = transcodes.isEmpty
        let response = try? await dataModel.linkedTranscodes(page: page)
        transcodes = response?.transcodes ?? []
        totalPages = response?.totalPages ?? 0
        loading = false
    }

    private func loadMore() async {
        page += 1
        let response = try? await dataModel.linkedTranscodes(page: page)
        transcodes += response?.transcodes ?? []
        totalPages = response?.totalPages ?? 0
    }

    private func unlinkTranscode(_ tc: AdminLinkedTranscode) async {
        try? await dataModel.unlinkTranscode(id: tc.transcodeId)
        transcodes.removeAll { $0.transcodeId == tc.transcodeId }
    }
}
