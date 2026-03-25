import SwiftUI

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
                }
                .environment(\.editMode, .constant(.active))
            }
        }
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
        do {
            let response = try await dataModel.adminListCameras()
            cameras = response.cameras.sorted { $0.displayOrder < $1.displayOrder }
            loading = false
        } catch {
            loading = false
        }
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
            CachedImage(
                ref: .cameraSnapshot(cameraId: camera.id),
                cornerRadius: 6,
                contentMode: .fill
            )
            .frame(width: 80, height: 45)
            .clipped()

            VStack(alignment: .leading, spacing: 4) {
                Text(camera.name)
                    .font(.subheadline)
                    .fontWeight(.medium)

                Text(camera.streamName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if !camera.enabled {
                Text("Disabled")
                    .font(.caption2)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(.red.opacity(0.15))
                    .foregroundStyle(.red)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Add/Edit Sheet

extension MMAdminCamera: Identifiable {}

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

                Section {
                    TextField("RTSP URL", text: $rtspUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !isAdd {
                        Text("Credentials shown as *** — leave unchanged to keep, or enter new ones")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    TextField("Snapshot URL (optional)", text: $snapshotUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    TextField("Stream Name", text: $streamName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("go2rtc stream identifier")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Toggle("Enabled", isOn: $enabled)
                }
            }
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
