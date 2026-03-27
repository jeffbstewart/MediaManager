import SwiftUI

struct LiveTvSettingsView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var settings: MMLiveTvSettingsResponse?
    @State private var tuners: [MMTunerResponse] = []
    @State private var loading = true
    @State private var maxStreams: Int32 = 4
    @State private var idleTimeout: Int32 = 60
    @State private var showAddTuner = false
    @State private var selectedTuner: MMTunerResponse?
    @State private var statusMessage: String?

    var body: some View {
        List {
            if let settings {
                Section("Streaming Limits") {
                    Stepper("Max Streams: \(maxStreams)", value: $maxStreams, in: 1...10)
                    Stepper("Idle Timeout: \(idleTimeout)s", value: $idleTimeout, in: 5...300, step: 5)
                    LabeledContent("Active Tuners", value: "\(settings.activeTunerCount)")
                    LabeledContent("Active Streams", value: "\(settings.activeStreamCount)")

                    Button("Save Settings") { Task { await saveSettings() } }
                }
            }

            Section("Tuners") {
                ForEach(tuners, id: \.id) { tuner in
                    Button {
                        selectedTuner = tuner
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(tuner.name)
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                            HStack(spacing: 8) {
                                Text(tuner.ipAddress)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if tuner.hasModelNumber {
                                    Text(tuner.modelNumber)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text("\(tuner.channelCount) ch")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if !tuner.enabled {
                                    Text("Disabled")
                                        .font(.caption2)
                                        .padding(.horizontal, 4)
                                        .padding(.vertical, 1)
                                        .background(.red.opacity(0.2))
                                        .foregroundStyle(.red)
                                        .clipShape(Capsule())
                                }
                            }
                        }
                    }
                }
            }

            if let statusMessage {
                Section { Text(statusMessage).font(.callout).foregroundStyle(.green) }
            }
        }
        .navigationTitle("Live TV Settings")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showAddTuner = true } label: { Image(systemName: "plus") }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showAddTuner) {
            AddTunerSheet { Task { await load() } }
        }
        .sheet(item: $selectedTuner) { tuner in
            TunerDetailSheet(tuner: tuner) { Task { await load() } }
        }
    }

    private func load() async {
        loading = true
        do {
            settings = try await authManager.grpcClient.adminGetLiveTvSettings()
            maxStreams = settings?.maxStreams ?? 4
            idleTimeout = settings?.idleTimeoutSeconds ?? 60
            let tunerResponse = try await authManager.grpcClient.adminListTuners()
            tuners = tunerResponse.tuners
        } catch {}
        loading = false
    }

    private func saveSettings() async {
        do {
            try await authManager.grpcClient.adminUpdateLiveTvSettings(
                minRating: nil, maxStreams: maxStreams, idleTimeout: idleTimeout)
            statusMessage = "Settings saved"
        } catch {}
    }
}

extension MMTunerResponse: Identifiable {}

struct AddTunerSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let onDone: () -> Void
    @State private var ipAddress = ""
    @State private var name = "HDHomeRun"
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("IP Address", text: $ipAddress)
                    .keyboardType(.decimalPad)
                TextField("Name", text: $name)
            }
            .navigationTitle("Add Tuner")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        saving = true
                        Task {
                            _ = try? await authManager.grpcClient.adminAddTuner(
                                ipAddress: ipAddress, name: name)
                            onDone()
                            dismiss()
                        }
                    }
                    .disabled(ipAddress.isEmpty || saving)
                }
            }
        }
    }
}

struct TunerDetailSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let tuner: MMTunerResponse
    let onDone: () -> Void

    @State private var channels: [MMAdminChannelResponse] = []
    @State private var loading = true
    @State private var statusMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section("Tuner") {
                    LabeledContent("IP Address", value: tuner.ipAddress)
                    if tuner.hasModelNumber { LabeledContent("Model", value: tuner.modelNumber) }
                    LabeledContent("Tuners", value: "\(tuner.tunerCount)")

                    Button("Refresh Channels") {
                        Task { await refreshChannels() }
                    }

                    Button("Delete Tuner", role: .destructive) {
                        Task { await deleteTuner() }
                    }
                }

                if !channels.isEmpty {
                    Section("Channels (\(channels.count))") {
                        ForEach(channels, id: \.id) { ch in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("\(ch.guideNumber) \(ch.guideName)")
                                        .font(.subheadline)
                                    HStack(spacing: 4) {
                                        if ch.hasNetworkAffiliation {
                                            Text(ch.networkAffiliation)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        Text(String(repeating: "\u{2605}", count: Int(ch.receptionQuality)))
                                            .font(.caption)
                                    }
                                }
                                Spacer()
                                if !ch.enabled {
                                    Text("Off")
                                        .font(.caption2)
                                        .foregroundStyle(.red)
                                }
                            }
                        }
                    }
                }

                if let statusMessage {
                    Section { Text(statusMessage).font(.callout).foregroundStyle(.green) }
                }
            }
            .navigationTitle(tuner.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { onDone(); dismiss() }
                }
            }
            .task { await loadChannels() }
        }
    }

    private func loadChannels() async {
        loading = true
        do {
            let response = try await authManager.grpcClient.adminListAdminChannels(tunerId: tuner.id)
            channels = response.channels
        } catch {}
        loading = false
    }

    private func refreshChannels() async {
        do {
            let result = try await authManager.grpcClient.adminRefreshTunerChannels(tunerId: tuner.id)
            statusMessage = "Found \(result.channelsFound), added \(result.channelsAdded)"
            await loadChannels()
        } catch {
            statusMessage = "Refresh failed"
        }
    }

    private func deleteTuner() async {
        do {
            try await authManager.grpcClient.adminDeleteTuner(id: tuner.id)
            onDone()
            dismiss()
        } catch {}
    }
}
