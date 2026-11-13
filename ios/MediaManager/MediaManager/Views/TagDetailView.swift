import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct TagDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.marquee) private var layout
    let route: TagRoute
    @State private var detail: ApiTagDetail?
    @State private var loading = true

    private var columns: [GridItem] {
        layout.gridColumns
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: layout.sectionGap) {
                        ForEach(detail.titles) { title in
                            NavigationLink(value: title) {
                                PosterCard(title: title, width: layout.gridCellWidth)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                }
                .mmGlowPage(MarqueeTheme.Colors.glowDiscover)
            } else {
                ContentUnavailableView("Tag not found", systemImage: "tag")
                    .mmPage()
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
        }
    }

    private func loadDetail() async {
        loading = true
        detail = try? await dataModel.tagDetail(id: route.id)
        loading = false
    }
}
