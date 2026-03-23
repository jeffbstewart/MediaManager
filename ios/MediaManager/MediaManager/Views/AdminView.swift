import SwiftUI

struct AdminView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var pendingWork: PendingWork?
    @State private var activeLeases: [ActiveLeaseInfo] = []
    @State private var buddyStatus: BuddyStatusResponse?
    @State private var loading = true
    @State private var scanning = false
    @State private var clearing = false
    @State private var statusMessage: String?
    @State private var monitorTask: Task<Void, Never>?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else {
                List {
                    // Pending work
                    if let pending = pendingWork, pending.total > 0 {
                        Section("Pending Work") {
                            if pending.transcodes > 0 {
                                Label("\(pending.transcodes) transcodes", systemImage: "film")
                            }
                            if let mobile = pending.mobileTranscodes, mobile > 0 {
                                Label("\(mobile) mobile", systemImage: "iphone")
                            }
                            if pending.thumbnails > 0 {
                                Label("\(pending.thumbnails) thumbnails", systemImage: "photo")
                            }
                            if pending.subtitles > 0 {
                                Label("\(pending.subtitles) subtitles", systemImage: "captions.bubble")
                            }
                            if pending.chapters > 0 {
                                Label("\(pending.chapters) chapters", systemImage: "list.bullet")
                            }
                        }
                    } else {
                        Section("Pending Work") {
                            Label("All caught up", systemImage: "checkmark.circle")
                                .foregroundStyle(.green)
                        }
                    }

                    // Active work
                    if !activeLeases.isEmpty {
                        Section("Active Transcodes") {
                            ForEach(activeLeases) { lease in
                                LeaseRow(
                                    path: lease.relativePath,
                                    type: lease.leaseType,
                                    progress: lease.progressPercent,
                                    encoder: lease.encoder,
                                    buddy: lease.buddyName
                                )
                            }
                        }
                    }

                    // Buddies
                    if let buddies = buddyStatus?.buddies, !buddies.isEmpty {
                        Section("Transcode Buddies") {
                            ForEach(buddies) { buddy in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Label(buddy.name ?? "Unknown", systemImage: "desktopcomputer")
                                            .fontWeight(.medium)
                                        Spacer()
                                        Text("\(buddy.activeLeases) active")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    ForEach(buddy.currentWork) { work in
                                        LeaseRow(
                                            path: work.relativePath,
                                            type: work.leaseType,
                                            progress: work.progressPercent,
                                            encoder: work.encoder,
                                            buddy: nil
                                        )
                                        .padding(.leading)
                                    }
                                }
                            }
                        }
                    }

                    // Actions
                    Section("Actions") {
                        Button {
                            Task { await scanNas() }
                        } label: {
                            HStack {
                                Label("Scan NAS", systemImage: "externaldrive")
                                Spacer()
                                if scanning {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(scanning)

                        Button {
                            Task { await clearFailures() }
                        } label: {
                            HStack {
                                Label("Clear Failures", systemImage: "trash")
                                Spacer()
                                if clearing {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(clearing)

                        if let statusMessage {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundStyle(.green)
                        }
                    }

                    // Recent activity
                    if let recent = buddyStatus?.recentLeases, !recent.isEmpty {
                        Section("Recent Activity") {
                            ForEach(recent) { lease in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(lease.relativePath?.components(separatedBy: "/").last ?? "Unknown")
                                        .font(.subheadline)
                                        .lineLimit(1)

                                    HStack(spacing: 8) {
                                        if let type = lease.leaseType {
                                            Text(typeLabel(type))
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        if let status = lease.status {
                                            Text(status)
                                                .font(.caption)
                                                .fontWeight(.medium)
                                                .foregroundStyle(status == "COMPLETED" ? .green : .red)
                                        }
                                        if let buddy = lease.buddyName {
                                            Text(buddy)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }

                                    if let error = lease.errorMessage {
                                        Text(error)
                                            .font(.caption)
                                            .foregroundStyle(.red)
                                            .lineLimit(2)
                                    }
                                }
                                .padding(.vertical, 2)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Admin")
        .task {
            // Fetch buddy status (one-shot) while starting the monitor stream
            async let b = try? dataModel.buddyStatus()
            startMonitor()
            buddyStatus = await b
        }
        .onDisappear {
            monitorTask?.cancel()
            monitorTask = nil
        }
        .refreshable {
            // Cancel existing monitor and restart
            monitorTask?.cancel()
            monitorTask = nil
            async let b = try? dataModel.buddyStatus()
            startMonitor()
            buddyStatus = await b
        }
    }

    private func startMonitor() {
        monitorTask = Task {
            do {
                try await dataModel.monitorTranscodeStatus { update in
                    await MainActor.run {
                        handleUpdate(update)
                    }
                }
            } catch is CancellationError {
                // Normal — view disappeared or refresh triggered
            } catch {
                // Stream ended or failed — fall back to one-shot fetch
                if !Task.isCancelled {
                    let status = try? await dataModel.transcodeStatus()
                    await MainActor.run {
                        if let status {
                            applySnapshot(status)
                        }
                    }
                }
            }
        }
    }

    private func handleUpdate(_ update: MMTranscodeStatusUpdate) {
        switch update.update {
        case .snapshot(let proto):
            let status = TranscodeStatusResponse(proto: proto)
            applySnapshot(status)
        case .event(let event):
            applyEvent(event)
        case nil:
            break
        }
    }

    private func applySnapshot(_ status: TranscodeStatusResponse) {
        pendingWork = status.pending
        activeLeases = status.activeLeases.map { ActiveLeaseInfo(from: $0) }
        loading = false
    }

    private func applyEvent(_ event: MMTranscodeProgressEvent) {
        let leaseId = event.leaseID
        let statusStr = event.status.displayString

        switch event.status {
        case .claimed:
            // New lease — add if not already present
            if !activeLeases.contains(where: { $0.id == leaseId }) {
                activeLeases.append(ActiveLeaseInfo(from: event))
            }
        case .inProgress:
            // Update progress on existing lease
            if let idx = activeLeases.firstIndex(where: { $0.id == leaseId }) {
                activeLeases[idx].progressPercent = Int(event.progressPercent)
                if event.hasEncoder {
                    activeLeases[idx].encoder = event.encoder
                }
                activeLeases[idx].status = statusStr
            } else {
                // Lease appeared mid-stream (e.g. monitor started after claim)
                activeLeases.append(ActiveLeaseInfo(from: event))
            }
        case .completed, .failed, .expired:
            // Remove from active — a snapshot follows immediately with updated pending counts
            activeLeases.removeAll { $0.id == leaseId }
        default:
            break
        }
    }

    private func scanNas() async {
        scanning = true
        statusMessage = nil
        do {
            try await dataModel.scanNas()
            statusMessage = "NAS scan started"
        } catch {
            statusMessage = "Scan failed"
        }
        scanning = false
        dismissStatusAfterDelay()
    }

    private func clearFailures() async {
        clearing = true
        statusMessage = nil
        try? await dataModel.clearFailures()
        statusMessage = "Failures cleared"
        clearing = false
        dismissStatusAfterDelay()
        // Restart monitor to get fresh snapshot
        monitorTask?.cancel()
        startMonitor()
    }

    private func dismissStatusAfterDelay() {
        Task {
            try? await Task.sleep(for: .seconds(3))
            statusMessage = nil
        }
    }

    private func typeLabel(_ type: String) -> String {
        switch type {
        case "TRANSCODE": "Browser"
        case "MOBILE_TRANSCODE": "Mobile"
        case "THUMBNAILS": "Thumbs"
        case "SUBTITLES": "Subs"
        case "CHAPTERS": "Chapters"
        default: type
        }
    }
}

// MARK: - Mutable lease state for live updates

struct ActiveLeaseInfo: Identifiable {
    var id: Int64
    var buddyName: String?
    var relativePath: String?
    var leaseType: String?
    var status: String?
    var progressPercent: Int
    var encoder: String?

    init(from lease: TranscodeLease) {
        self.id = lease.leaseId.protoValue
        self.buddyName = lease.buddyName
        self.relativePath = lease.relativePath
        self.leaseType = lease.leaseType
        self.status = lease.status
        self.progressPercent = lease.progressPercent ?? 0
        self.encoder = lease.encoder
    }

    init(from event: MMTranscodeProgressEvent) {
        self.id = event.leaseID
        self.buddyName = event.buddyName.isEmpty ? nil : event.buddyName
        self.relativePath = event.relativePath.isEmpty ? nil : event.relativePath
        self.leaseType = event.leaseType.displayString
        self.status = event.status.displayString
        self.progressPercent = Int(event.progressPercent)
        self.encoder = event.hasEncoder ? event.encoder : nil
    }
}

// MARK: - Lease Row

struct LeaseRow: View {
    let path: String?
    let type: String?
    let progress: Int?
    let encoder: String?
    let buddy: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(path?.components(separatedBy: "/").last ?? "Unknown")
                .font(.subheadline)
                .lineLimit(1)

            HStack(spacing: 8) {
                if let type {
                    Text(typeLabel(type))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let encoder {
                    Text(encoder)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let buddy {
                    Text(buddy)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let progress {
                ProgressView(value: Double(progress), total: 100)
                    .tint(progress >= 90 ? .green : .blue)
            }
        }
        .padding(.vertical, 2)
    }

    private func typeLabel(_ type: String) -> String {
        switch type {
        case "TRANSCODE": "Browser"
        case "MOBILE_TRANSCODE": "Mobile"
        case "THUMBNAILS": "Thumbs"
        case "SUBTITLES": "Subs"
        case "CHAPTERS": "Chapters"
        default: type
        }
    }
}
