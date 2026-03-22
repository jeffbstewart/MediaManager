import SwiftUI

struct AdminView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var transcodeStatus: TranscodeStatusResponse?
    @State private var buddyStatus: BuddyStatusResponse?
    @State private var loading = true
    @State private var scanning = false
    @State private var clearing = false
    @State private var statusMessage: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else {
                List {
                    // Pending work
                    if let pending = transcodeStatus?.pending, pending.total > 0 {
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
                    if let leases = transcodeStatus?.activeLeases, !leases.isEmpty {
                        Section("Active Transcodes") {
                            ForEach(leases) { lease in
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
            await loadStatus()
        }
        .refreshable {
            await loadStatus()
        }
    }

    private func loadStatus() async {
        loading = transcodeStatus == nil
        async let t = try? dataModel.transcodeStatus()
        async let b = try? dataModel.buddyStatus()
        transcodeStatus = await t
        buddyStatus = await b
        loading = false
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
        await loadStatus()
    }

    private func dismissStatusAfterDelay() {
        Task {
            try? await Task.sleep(for: .seconds(3))
            statusMessage = nil
        }
    }

    private func typeLabel(_ type: String) -> String {
        switch type {
        case "BROWSER_TRANSCODE": "Browser"
        case "MOBILE_TRANSCODE": "Mobile"
        case "THUMBNAIL": "Thumbs"
        case "SUBTITLE": "Subs"
        case "CHAPTER": "Chapters"
        default: type
        }
    }
}

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
        case "BROWSER_TRANSCODE": "Browser"
        case "MOBILE_TRANSCODE": "Mobile"
        case "THUMBNAIL": "Thumbs"
        case "SUBTITLE": "Subs"
        case "CHAPTER": "Chapters"
        default: type
        }
    }
}
