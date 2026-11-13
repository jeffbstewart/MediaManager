import SwiftUI
import MediaManagerCore
import MediaManagerProtos

// ApiSession and ApiSessionListResponse defined in ProtoAdapters.swift

struct SessionsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss
    @State private var sessions: [ApiSession] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading...")
                } else if sessions.isEmpty {
                    ContentUnavailableView("No sessions", systemImage: "person.badge.key")
                } else {
                    List {
                        ForEach(sessions) { session in
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Image(systemName: iconFor(session.type))
                                            .foregroundStyle(MarqueeTheme.Colors.text3)
                                        Text(session.deviceName ?? session.type)
                                            .fontWeight(.medium)
                                        if session.isCurrent {
                                            Text("Current")
                                                .font(MarqueeTheme.Fonts.meta)
                                                .fontWeight(.bold)
                                                .foregroundStyle(.white)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(MarqueeTheme.Colors.green)
                                                .clipShape(Capsule())
                                        }
                                    }
                                    if let lastUsed = session.lastUsedAt {
                                        Text(formatDate(lastUsed))
                                            .font(MarqueeTheme.Fonts.meta)
                                            .foregroundStyle(MarqueeTheme.Colors.text3)
                                    }
                                    Text(session.type)
                                        .font(MarqueeTheme.Fonts.meta)
                                        .foregroundStyle(MarqueeTheme.Colors.text3)
                                }

                                Spacer()

                                if !session.isCurrent {
                                    Button {
                                        Task { await revokeSession(session) }
                                    } label: {
                                        Text("Revoke")
                                            .font(MarqueeTheme.Fonts.meta)
                                            .foregroundStyle(MarqueeTheme.Colors.pink)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .mmRow()

                        Section {
                            Button("Revoke All Other Sessions", role: .destructive) {
                                Task { await revokeAllOthers() }
                            }
                        }
                        .mmRow()
                    }
                }
            }
            .mmListChrome()
            .navigationTitle("Active Sessions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                await loadSessions()
            }
        }
    }

    private func iconFor(_ type: String) -> String {
        switch type {
        case "browser": "globe"
        case "app": "iphone"
        case "device": "tv"
        default: "questionmark.circle"
        }
    }

    private func formatDate(_ iso: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: iso) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: iso) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        return iso
    }

    private func loadSessions() async {
        loading = true
        let response = try? await dataModel.sessions()
        sessions = response?.sessions ?? []
        loading = false
    }

    private func revokeSession(_ session: ApiSession) async {
        let sessionType = SessionType(rawValue: session.type) ?? .access
        try? await dataModel.deleteSession(id: session.sessionId, type: sessionType)
        await loadSessions()
    }

    private func revokeAllOthers() async {
        try? await dataModel.deleteOtherSessions()
        await loadSessions()
    }
}
