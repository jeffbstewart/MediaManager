import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct AdminCamerasView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var cameras: [MMAdminCamera] = []
    @State private var loading = true
    @State private var showAddSheet = false
    @State private var editingCamera: MMAdminCamera?
    @State private var deleteTarget: MMAdminCamera?
    @State private var statusMessage: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if cameras.isEmpty {
                ContentUnavailableView("No Cameras", systemImage: "web.camera",
                    description: Text("Tap + to add a camera"))
            } else {
                List {
                    ForEach(cameras, id: \.id) { camera in
                        CameraAdminRow(camera: camera)
                            .contentShape(Rectangle())
                            .onTapGesture { editingCamera = camera }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    deleteTarget = camera
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                    .onMove { source, destination in
                        cameras.move(fromOffsets: source, toOffset: destination)
                        saveOrder()
                    }
                    .mmRow()
                }
                .environment(\.editMode, .constant(.active))
            }
        }
        .mmListChrome()
        .navigationTitle("Camera Admin")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task { await loadCameras() }
        .refreshable { await loadCameras() }
        .sheet(isPresented: $showAddSheet) {
            CameraEditSheet(mode: .add) { name, rtspUrl, snapshotUrl, streamName, enabled in
                Task {
                    _ = try? await dataModel.adminCreateCamera(
                        name: name, rtspUrl: rtspUrl, snapshotUrl: snapshotUrl,
                        streamName: streamName, enabled: enabled
                    )
                    await loadCameras()
                }
            }
        }
        .sheet(item: $editingCamera) { camera in
            CameraEditSheet(mode: .edit(camera)) { name, rtspUrl, snapshotUrl, streamName, enabled in
                Task {
                    _ = try? await dataModel.adminUpdateCamera(
                        id: camera.id, name: name, rtspUrl: rtspUrl,
                        snapshotUrl: snapshotUrl, streamName: streamName ?? "", enabled: enabled
                    )
                    await loadCameras()
                }
            }
        }
        .alert("Delete Camera", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    Task {
                        try? await dataModel.adminDeleteCamera(id: target.id)
                        await loadCameras()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Delete '\(deleteTarget?.name ?? "")'? This cannot be undone.")
        }
    }

    private func loadCameras() async {
        loading = cameras.isEmpty
        do {
            let response = try await dataModel.adminListCameras()
            cameras = response.cameras.sorted { $0.displayOrder < $1.displayOrder }
        } catch {
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }

    private func saveOrder() {
        let ids = cameras.map { $0.id }
        Task {
            try? await dataModel.adminReorderCameras(ids: ids)
        }
    }
}

// MARK: - Camera Row

struct CameraAdminRow: View {
    let camera: MMAdminCamera

    var body: some View {
        HStack(spacing: 12) {
            // Snapshot thumbnail
            MMArtFrame {
                CachedImage(
                    ref: .cameraSnapshot(cameraId: camera.id),
                    cornerRadius: 0,
                    contentMode: .fill
                )
            }
            .frame(width: 80, height: 45)

            VStack(alignment: .leading, spacing: 4) {
                Text(camera.name)
                    .font(MarqueeTheme.Fonts.cardTitle)
                    .fontWeight(.medium)

                Text(camera.streamName)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
            }

            Spacer()

            if !camera.enabled {
                Text("Disabled")
                    .font(MarqueeTheme.Fonts.meta)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(MarqueeTheme.Colors.live.opacity(0.15))
                    .foregroundStyle(MarqueeTheme.Colors.pink)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Add/Edit Sheet

enum CameraEditMode {
    case add
    case edit(MMAdminCamera)
}

struct CameraEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    let mode: CameraEditMode
    let onSave: (String, String, String, String?, Bool) -> Void

    @State private var name = ""
    @State private var rtspUrl = ""
    @State private var snapshotUrl = ""
    @State private var streamName = ""
    @State private var enabled = true

    private var isAdd: Bool {
        if case .add = mode { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                        .onChange(of: name) {
                            if isAdd || streamName.isEmpty {
                                streamName = generateStreamName(name)
                            }
                        }
                }
                .mmRow()

                Section {
                    TextField("RTSP URL", text: $rtspUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !isAdd {
                        Text("Credentials shown as *** — leave unchanged to keep, or enter new ones")
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                    }
                }
                .mmRow()

                Section {
                    TextField("Snapshot URL (optional)", text: $snapshotUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .mmRow()

                Section {
                    TextField("Stream Name", text: $streamName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("go2rtc stream identifier")
                        .font(MarqueeTheme.Fonts.meta)
                        .foregroundStyle(MarqueeTheme.Colors.text3)
                }
                .mmRow()

                Section {
                    Toggle("Enabled", isOn: $enabled)
                }
                .mmRow()
            }
            .mmListChrome()
            .navigationTitle(isAdd ? "Add Camera" : "Edit Camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(name, rtspUrl, snapshotUrl, streamName.isEmpty ? nil : streamName, enabled)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty ||
                              rtspUrl.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                if case .edit(let camera) = mode {
                    name = camera.name
                    rtspUrl = camera.rtspURL
                    snapshotUrl = camera.snapshotURL
                    streamName = camera.streamName
                    enabled = camera.enabled
                }
            }
        }
    }

    private func generateStreamName(_ name: String) -> String {
        name.lowercased()
            .replacing(/[^a-z0-9]+/, with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
    }
}
