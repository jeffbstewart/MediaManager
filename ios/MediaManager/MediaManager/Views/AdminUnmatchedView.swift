import SwiftUI

struct AdminUnmatchedView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var files: [AdminUnmatchedFile] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if files.isEmpty {
                ContentUnavailableView("All files matched", systemImage: "checkmark.circle",
                    description: Text("No unmatched files on NAS."))
            } else {
                List {
                    ForEach(files) { file in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(file.fileName)
                                .fontWeight(.medium)
                                .lineLimit(2)

                            if let dir = file.directory {
                                Text(dir)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)
                            }

                            HStack(spacing: 8) {
                                if let parsed = file.parsedTitle {
                                    Text("Parsed: \(parsed)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let year = file.parsedYear {
                                    Text("(\(year))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let s = file.parsedSeason, let e = file.parsedEpisode {
                                    Text("S\(s)E\(e)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            if !file.suggestions.isEmpty {
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(file.suggestions) { suggestion in
                                        Button {
                                            Task { await linkToTitle(file, titleId: suggestion.titleId) }
                                        } label: {
                                            HStack {
                                                Image(systemName: "arrow.right.circle")
                                                    .font(.caption)
                                                Text(suggestion.titleName)
                                                    .font(.caption)
                                                Spacer()
                                                Text("\(Int(suggestion.score * 100))%")
                                                    .font(.caption2)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.top, 2)
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            Button("Ignore") {
                                Task { await ignoreFile(file) }
                            }
                            .tint(.gray)
                        }
                        .swipeActions(edge: .leading) {
                            if !file.suggestions.isEmpty {
                                Button("Accept") {
                                    Task { await acceptSuggestion(file) }
                                }
                                .tint(.green)
                            }
                        }
                        .contextMenu {
                            if !file.suggestions.isEmpty {
                                Button("Accept Top Suggestion") {
                                    Task { await acceptSuggestion(file) }
                                }
                            }
                            Button("Ignore File") {
                                Task { await ignoreFile(file) }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Unmatched Files")
        .task { await loadFiles() }
        .refreshable { await loadFiles() }
    }

    private func loadFiles() async {
        loading = files.isEmpty
        let response: AdminUnmatchedResponse? = try? await authManager.apiClient.get("admin/transcodes/unmatched")
        files = response?.unmatched ?? []
        loading = false
    }

    private func acceptSuggestion(_ file: AdminUnmatchedFile) async {
        try? await authManager.apiClient.post("admin/transcodes/unmatched/\(file.id)/accept", body: [:])
        files.removeAll { $0.id == file.id }
    }

    private func ignoreFile(_ file: AdminUnmatchedFile) async {
        try? await authManager.apiClient.post("admin/transcodes/unmatched/\(file.id)/ignore", body: [:])
        files.removeAll { $0.id == file.id }
    }

    private func linkToTitle(_ file: AdminUnmatchedFile, titleId: Int) async {
        try? await authManager.apiClient.post("admin/transcodes/unmatched/\(file.id)/link", body: ["title_id": titleId])
        files.removeAll { $0.id == file.id }
    }
}
