import SwiftUI

struct ApiSession: Codable, Identifiable {
    var id: String { "\(sessionId)-\(type)" }
    let sessionId: Int
    let type: String
    let deviceName: String?
    let lastActive: String?
    let isCurrent: Bool

    enum CodingKeys: String, CodingKey {
        case type
        case sessionId = "session_id"
        case deviceName = "device_name"
        case lastActive = "last_active"
        case isCurrent = "is_current"
    }
}

struct ApiSessionListResponse: Codable {
    let sessions: [ApiSession]
}

struct SessionsView: View {
    @Environment(AuthManager.self) private var authManager
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
                                            .foregroundStyle(.secondary)
                                        Text(session.deviceName ?? session.type)
                                            .fontWeight(.medium)
                                        if session.isCurrent {
                                            Text("Current")
                                                .font(.caption2)
                                                .fontWeight(.bold)
                                                .foregroundStyle(.white)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(.green)
                                                .clipShape(Capsule())
                                        }
                                    }
                                    if let lastActive = session.lastActive {
                                        Text(lastActive)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Text(session.type)
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }

                                Spacer()

                                if !session.isCurrent {
                                    Button {
                                        Task { await revokeSession(session) }
                                    } label: {
                                        Text("Revoke")
                                            .font(.caption)
                                            .foregroundStyle(.red)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }

                        Section {
                            Button("Revoke All Other Sessions", role: .destructive) {
                                Task { await revokeAllOthers() }
                            }
                        }
                    }
                }
            }
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

    private func loadSessions() async {
        loading = true
        let response: ApiSessionListResponse? = try? await authManager.apiClient.get("sessions")
        sessions = response?.sessions ?? []
        loading = false
    }

    private func revokeSession(_ session: ApiSession) async {
        try? await authManager.apiClient.delete("sessions/\(session.sessionId)?type=\(session.type)")
        await loadSessions()
    }

    private func revokeAllOthers() async {
        try? await authManager.apiClient.delete("sessions?scope=others")
        await loadSessions()
    }
}
