import SwiftUI

struct FamilyMembersView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var members: [MMFamilyMemberResponse] = []
    @State private var loading = true
    @State private var showAdd = false
    @State private var editingMember: MMFamilyMemberResponse?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if members.isEmpty {
                ContentUnavailableView("No Family Members",
                    systemImage: "person.2",
                    description: Text("Add family members to tag personal videos."))
            } else {
                List(members, id: \.id) { member in
                    Button {
                        editingMember = member
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(member.name)
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                            HStack(spacing: 8) {
                                if member.hasBirthDate {
                                    Text(member.birthDate)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text("\(member.videoCount) videos")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if member.hasNotes {
                                    Text(member.notes)
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Family Members")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showAdd = true } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showAdd) {
            FamilyMemberEditSheet(onSave: { Task { await load() } })
        }
        .sheet(item: $editingMember) { member in
            FamilyMemberEditSheet(existing: member, onSave: { Task { await load() } })
        }
    }

    private func load() async {
        loading = true
        do {
            let response = try await authManager.grpcClient.adminListFamilyMembers()
            members = response.members
        } catch {}
        loading = false
    }
}

extension MMFamilyMemberResponse: Identifiable {}

struct FamilyMemberEditSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    var existing: MMFamilyMemberResponse?
    let onSave: () -> Void

    @State private var name = ""
    @State private var birthDate = ""
    @State private var notes = ""
    @State private var saving = false
    @State private var error: String?

    var isEditing: Bool { existing != nil }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                TextField("Birth Date (YYYY-MM-DD)", text: $birthDate)
                TextField("Notes", text: $notes, axis: .vertical)
                    .lineLimit(3...6)

                if let error {
                    Text(error).foregroundStyle(.red).font(.callout)
                }

                if isEditing {
                    Section {
                        Button("Delete", role: .destructive) {
                            Task { await delete() }
                        }
                    }
                }
            }
            .navigationTitle(isEditing ? "Edit Member" : "Add Member")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(name.isEmpty || saving)
                }
            }
            .onAppear {
                if let e = existing {
                    name = e.name
                    birthDate = e.hasBirthDate ? e.birthDate : ""
                    notes = e.hasNotes ? e.notes : ""
                }
            }
        }
    }

    private func save() async {
        saving = true
        error = nil
        do {
            if let e = existing {
                try await authManager.grpcClient.adminUpdateFamilyMember(
                    id: e.id, name: name,
                    birthDate: birthDate.isEmpty ? nil : birthDate,
                    notes: notes.isEmpty ? nil : notes)
            } else {
                _ = try await authManager.grpcClient.adminCreateFamilyMember(
                    name: name,
                    birthDate: birthDate.isEmpty ? nil : birthDate,
                    notes: notes.isEmpty ? nil : notes)
            }
            onSave()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        saving = false
    }

    private func delete() async {
        guard let e = existing else { return }
        do {
            try await authManager.grpcClient.adminDeleteFamilyMember(id: e.id)
            onSave()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
