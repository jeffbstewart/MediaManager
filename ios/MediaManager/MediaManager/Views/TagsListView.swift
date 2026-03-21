import SwiftUI

struct TagRoute: Hashable {
    let id: Int
    let name: String
}

struct TagsListView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var tags: [ApiTagListItem] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if tags.isEmpty {
                ContentUnavailableView("No tags", systemImage: "tag")
            } else {
                List(tags) { tag in
                    NavigationLink(value: TagRoute(id: tag.id, name: tag.name)) {
                        HStack {
                            Circle()
                                .fill(Color(hex: tag.color) ?? .gray)
                                .frame(width: 12, height: 12)
                            Text(tag.name)
                                .fontWeight(.medium)
                            Spacer()
                            Text("\(tag.titleCount) titles")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Tags")
        .task {
            await loadTags()
        }
        .refreshable {
            await loadTags()
        }
    }

    private func loadTags() async {
        loading = tags.isEmpty
        let response: ApiTagListResponse? = try? await authManager.apiClient.get("catalog/tags")
        tags = response?.tags ?? []
        loading = false
    }
}

// Helper to parse hex color strings
extension Color {
    init?(hex: String) {
        var cleaned = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("#") { cleaned.removeFirst() }
        guard cleaned.count == 6, let rgb = UInt64(cleaned, radix: 16) else { return nil }
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }
}
