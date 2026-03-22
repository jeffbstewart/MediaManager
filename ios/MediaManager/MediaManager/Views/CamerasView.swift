import SwiftUI
import AVKit

struct CamerasView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var cameras: [ApiCamera] = []
    @State private var loading = true
    @State private var selectedCamera: ApiCamera?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if cameras.isEmpty {
                ContentUnavailableView("No cameras", systemImage: "video.slash",
                    description: Text("No cameras are configured on the server."))
            } else {
                List(cameras) { camera in
                    Button {
                        selectedCamera = camera
                    } label: {
                        HStack(spacing: 12) {
                            // Snapshot preview
                            AuthenticatedImage(
                                path: camera.snapshotUrl,
                                apiClient: dataModel.apiClient,
                                cornerRadius: 6
                            )
                            .frame(width: 80, height: 45)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(camera.name)
                                    .fontWeight(.medium)
                                    .foregroundStyle(.primary)
                                Text("Live")
                                    .font(.caption)
                                    .foregroundStyle(.green)
                            }

                            Spacer()

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
                title: camera.name
            )
        }
    }

    private func loadCameras() async {
        loading = cameras.isEmpty
        let response = try? await dataModel.cameras()
        cameras = response?.cameras ?? []
        loading = false
    }
}
