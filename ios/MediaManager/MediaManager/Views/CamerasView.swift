import SwiftUI
import AVKit
import MediaManagerCore
import MediaManagerProtos

struct CamerasView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var cameras: [ApiCamera] = []
    @State private var loading = true
    @State private var selectedCamera: ApiCamera?
    @Environment(\.marquee) private var layout

    private var columns: [GridItem] {
        layout.wideGridColumns
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if cameras.isEmpty {
                ContentUnavailableView("No cameras", systemImage: "video.slash",
                    description: Text("No cameras are configured on the server."))
            } else {
                // Camera tiles (spec 3.3): the latest snapshot as a
                // wide card with the LIVE badge; tapping opens the
                // stream.
                ScrollView {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: layout.sectionGap) {
                        ForEach(cameras) { camera in
                            Button {
                                selectedCamera = camera
                            } label: {
                                MMWideCard(
                                    name: camera.name,
                                    ref: .cameraSnapshot(cameraId: camera.id.protoValue),
                                    badge: "Live",
                                    badgeTone: .live,
                                    showPlay: true,
                                    width: layout.wideGridCellWidth)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Camera \(camera.name)")
                        }
                    }
                    .padding(.horizontal, layout.pageX)
                    .padding(.vertical, 16)
                }
            }
        }
        .mmListChrome()
        .navigationTitle("Cameras")
        .task {
            await loadCameras()
        }
        .refreshable {
            await loadCameras()
        }
        .fullScreenCover(item: $selectedCamera) { camera in
            LiveStreamView(
                streamPath: camera.hlsUrl,
                title: camera.name,
                onClose: { selectedCamera = nil }
            )
        }
    }

    private func loadCameras() async {
        loading = cameras.isEmpty
        do {
            let response = try await dataModel.cameras()
            cameras = response.cameras
        } catch {
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }
}
