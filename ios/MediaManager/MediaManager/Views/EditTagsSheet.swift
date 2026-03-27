import SwiftUI

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
                    Section("Assigned") {
                        let assigned = allTags.filter { assignedTagIds.contains($0.id.protoValue) }
                        if assigned.isEmpty {
                            Text("No tags assigned")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(assigned) { tag in
                                HStack {
                                    Text(tag.name)
                                    Spacer()
                                    Button(role: .destructive) {
                                        Task { await removeTag(tag) }
                                    } label: {
                                        Image(systemName: "minus.circle.fill")
                                            .foregroundStyle(.red)
                                    }
                                }
                            }
                        }
                    }

                    Section("Available") {
                        let available = allTags.filter { !assignedTagIds.contains($0.id.protoValue) }
                        if available.isEmpty {
                            Text("All tags assigned")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(available) { tag in
                                Button {
                                    Task { await addTag(tag) }
                                } label: {
                                    HStack {
                                        Text(tag.name)
                                            .foregroundStyle(.primary)
                                        Spacer()
                                        Image(systemName: "plus.circle")
                                            .foregroundStyle(.blue)
                                    }
                                }
                            }
                        }
                    }
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(.callout)
                            .foregroundStyle(.red)
                    }
                }
            }
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
