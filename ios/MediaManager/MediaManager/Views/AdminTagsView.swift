import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct AdminTagsView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var tags: [ApiTagListItem] = []
    @State private var loading = true
    @State private var showCreate = false
    @State private var editingTag: ApiTagListItem?
    @State private var tagToDelete: ApiTagListItem?

    var body: some View {
        List {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity)
                    .listRowSeparator(.hidden)
            } else if tags.isEmpty {
                ContentUnavailableView("No tags", systemImage: "tag")
                    .listRowSeparator(.hidden)
            } else {
                ForEach(tags) { tag in
                    HStack {
                        Circle()
                            .fill(Color(hex: tag.color) ?? MarqueeTheme.Colors.grey4)
                            .frame(width: 16, height: 16)
                        Text(tag.name)
                            .fontWeight(.medium)
                        Spacer()
                        Text("\(tag.titleCount)")
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)
                    }
                    .swipeActions(edge: .trailing) {
                        Button("Delete", role: .destructive) {
                            tagToDelete = tag
                        }
                        Button("Edit") {
                            editingTag = tag
                        }
                        .tint(MarqueeTheme.Colors.blue)
                    }
                    .contextMenu {
                        Button("Edit") { editingTag = tag }
                        Button("Delete", role: .destructive) {
                            tagToDelete = tag
                        }
                    }
                }
                .mmRow()
            }
        }
        .mmListChrome()
        .navigationTitle("Tags")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showCreate = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task { await loadTags() }
        .refreshable { await loadTags() }
        .sheet(isPresented: $showCreate) {
            AdminTagEditView(tag: nil) { await loadTags() }
        }
        .sheet(item: $editingTag) { tag in
            AdminTagEditView(tag: tag) { await loadTags() }
        }
        .alert("Delete Tag", isPresented: .init(
            get: { tagToDelete != nil },
            set: { if !$0 { tagToDelete = nil } }
        )) {
            Button("Cancel", role: .cancel) { tagToDelete = nil }
            Button("Delete", role: .destructive) {
                if let tag = tagToDelete {
                    Task { await deleteTag(tag) }
                }
            }
        } message: {
            Text("Are you sure you want to delete \"\(tagToDelete?.name ?? "")\"? This will remove the tag from all titles.")
        }
    }

    private func loadTags() async {
        loading = tags.isEmpty
        do {
            let response = try await dataModel.adminTags()
            tags = response.tags
        } catch {
            if Task.isCancelled || error is CancellationError { return }
        }
        loading = false
    }

    private func deleteTag(_ tag: ApiTagListItem) async {
        try? await dataModel.deleteTag(id: tag.id)
        tags.removeAll { $0.id == tag.id }
        tagToDelete = nil
    }
}

struct AdminTagEditView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss
    let tag: ApiTagListItem?
    let onComplete: () async -> Void

    @State private var name = ""
    @State private var color = "#4A90D9"
    @State private var error: String?
    @State private var saving = false

    private let presetColors = [
        "#4A90D9", "#E74C3C", "#2ECC71", "#F39C12",
        "#9B59B6", "#1ABC9C", "#E67E22", "#34495E"
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Tag Name", text: $name)
                }
                .mmRow()

                Section(header: MMSectionHeader("Color")) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 12) {
                        ForEach(presetColors, id: \.self) { c in
                            Circle()
                                .fill(Color(hex: c) ?? MarqueeTheme.Colors.grey4)
                                .frame(width: 40, height: 40)
                                .overlay {
                                    if c == color {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.white)
                                            .fontWeight(.bold)
                                    }
                                }
                                .onTapGesture { color = c }
                        }
                    }
                    .padding(.vertical, 4)
                }
                .mmRow()

                if let error {
                    Text(error).foregroundStyle(MarqueeTheme.Colors.pink).font(MarqueeTheme.Fonts.cardTitle)
                }

                Section {
                    Button(saving ? "Saving..." : (tag == nil ? "Create Tag" : "Save")) {
                        Task { await save() }
                    }
                    .buttonStyle(.mmPrimary)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(name.isEmpty || saving)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                .mmRow()
            }
            .mmListChrome()
            .navigationTitle(tag == nil ? "New Tag" : "Edit Tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                if let tag {
                    name = tag.name
                    color = tag.color
                }
            }
        }
    }

    private func save() async {
        saving = true
        error = nil
        do {
            if let tag {
                try await dataModel.updateTag(id: tag.id, name: name, color: color)
            } else {
                try await dataModel.createTag(name: name, color: color)
            }
            await onComplete()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }
}

