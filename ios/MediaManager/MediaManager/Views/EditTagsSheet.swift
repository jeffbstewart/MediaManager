import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct EditTagsSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    let titleId: Int64
    let currentTags: [ApiTag]
    let onChanged: () -> Void

    @State private var allTags: [ApiTagListItem] = []
    @State private var assignedTagIds: Set<Int64> = []
    @State private var loading = true
    @State private var statusMessage: String?

    var body: some View {
        NavigationStack {
            List {
                if loading {
                    ProgressView("Loading tags...")
                } else {
                    Section(header: MMSectionHeader("Assigned")) {
                        let assigned = allTags.filter { assignedTagIds.contains($0.id.protoValue) }
                        if assigned.isEmpty {
                            Text("No tags assigned")
                                .foregroundStyle(MarqueeTheme.Colors.text3)
                        } else {
                            ForEach(assigned) { tag in
                                HStack {
                                    Text(tag.name)
                                    Spacer()
                                    Button(role: .destructive) {
                                        Task { await removeTag(tag) }
                                    } label: {
                                        Image(systemName: "minus.circle.fill")
                                            .foregroundStyle(MarqueeTheme.Colors.pink)
                                    }
                                }
                            }
                        }
                    }
                    .mmRow()

                    Section(header: MMSectionHeader("Available")) {
                        let available = allTags.filter { !assignedTagIds.contains($0.id.protoValue) }
                        if available.isEmpty {
                            Text("All tags assigned")
                                .foregroundStyle(MarqueeTheme.Colors.text3)
                        } else {
                            ForEach(available) { tag in
                                Button {
                                    Task { await addTag(tag) }
                                } label: {
                                    HStack {
                                        Text(tag.name)
                                            .foregroundStyle(MarqueeTheme.Colors.text)
                                        Spacer()
                                        Image(systemName: "plus.circle")
                                            .foregroundStyle(MarqueeTheme.Colors.blue)
                                    }
                                }
                            }
                        }
                    }
                    .mmRow()
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(MarqueeTheme.Fonts.cardTitle)
                            .foregroundStyle(MarqueeTheme.Colors.pink)
                    }
                    .mmRow()
                }
            }
            .mmListChrome()
            .navigationTitle("Edit Tags")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onChanged()
                        dismiss()
                    }
                }
            }
            .task { await loadAllTags() }
        }
    }

    private func loadAllTags() async {
        loading = true
        assignedTagIds = Set(currentTags.map { $0.id.protoValue })
        do {
            let response = try await dataModel.tags()
            allTags = response.tags
        } catch {
            statusMessage = "Failed to load tags"
        }
        loading = false
    }

    private func addTag(_ tag: ApiTagListItem) async {
        do {
            try await authManager.grpcClient.adminAddTagToTitle(tagId: tag.id.protoValue, titleId: titleId)
            assignedTagIds.insert(tag.id.protoValue)
        } catch {
            statusMessage = "Failed to add tag"
        }
    }

    private func removeTag(_ tag: ApiTagListItem) async {
        do {
            try await authManager.grpcClient.adminRemoveTagFromTitle(tagId: tag.id.protoValue, titleId: titleId)
            assignedTagIds.remove(tag.id.protoValue)
        } catch {
            statusMessage = "Failed to remove tag"
        }
    }
}
