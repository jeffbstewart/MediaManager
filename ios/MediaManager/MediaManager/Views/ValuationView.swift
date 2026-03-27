import SwiftUI

struct ValuationView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var items: [MMValuationItem] = []
    @State private var loading = true
    @State private var searchQuery = ""
    @State private var unpricedOnly = false
    @State private var totalPurchase = 0.0
    @State private var totalReplacement = 0.0
    @State private var totalItems = 0
    @State private var editingItem: MMValuationItem?

    var body: some View {
        List {
            Section {
                LabeledContent("Total Items", value: "\(totalItems)")
                LabeledContent("Purchase Value", value: String(format: "$%.2f", totalPurchase))
                LabeledContent("Replacement Value", value: String(format: "$%.2f", totalReplacement))
            }

            Section {
                TextField("Search", text: $searchQuery)
                    .autocorrectionDisabled()
                    .onSubmit { load() }
                Toggle("Unpriced only", isOn: $unpricedOnly)
                    .onChange(of: unpricedOnly) { _, _ in load() }
            }

            if loading {
                Section { ProgressView("Loading...") }
            } else {
                Section("Items (\(items.count))") {
                    ForEach(items, id: \.mediaItemID) { item in
                        Button {
                            editingItem = item
                        } label: {
                            ValuationItemRow(item: item)
                        }
                    }
                }
            }
        }
        .navigationTitle("Valuation")
        .task { await loadData() }
        .sheet(item: $editingItem) { item in
            EditMediaItemSheet(item: item) { load() }
        }
    }

    private func load() { Task { await loadData() } }

    private func loadData() async {
        loading = true
        do {
            let response = try await authManager.grpcClient.adminListValuations(
                query: searchQuery, unpricedOnly: unpricedOnly)
            items = response.items
            totalPurchase = response.totalPurchaseValue
            totalReplacement = response.totalReplacementValue
            totalItems = Int(response.totalItems)
        } catch {}
        loading = false
    }
}

extension MMValuationItem: Identifiable {
    public var id: Int64 { mediaItemID }
}

struct ValuationItemRow: View {
    let item: MMValuationItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(item.titleNames.isEmpty ? item.productName : item.titleNames.joined(separator: ", "))
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)
            HStack(spacing: 8) {
                if item.hasPurchasePrice {
                    Text(String(format: "$%.2f", item.purchasePrice))
                        .font(.caption)
                        .foregroundStyle(.green)
                } else {
                    Text("No price")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                if item.hasReplacementValue {
                    Text(String(format: "Repl: $%.2f", item.replacementValue))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if item.hasPurchasePlace {
                    Text(item.purchasePlace)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if item.photoCount > 0 {
                    Label("\(item.photoCount)", systemImage: "camera")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

struct EditMediaItemSheet: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.dismiss) private var dismiss
    let item: MMValuationItem
    let onSave: () -> Void

    @State private var place: String = ""
    @State private var date: String = ""
    @State private var price: String = ""
    @State private var replacement: String = ""
    @State private var asin: String = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Purchase Info") {
                    TextField("Place", text: $place)
                    TextField("Date (YYYY-MM-DD)", text: $date)
                    TextField("Price", text: $price)
                        .keyboardType(.decimalPad)
                }
                Section("Replacement") {
                    TextField("Replacement Value", text: $replacement)
                        .keyboardType(.decimalPad)
                    TextField("Override ASIN", text: $asin)
                }
            }
            .navigationTitle("Edit Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(saving)
                }
            }
            .onAppear {
                place = item.hasPurchasePlace ? item.purchasePlace : ""
                date = item.hasPurchaseDate ? item.purchaseDate : ""
                price = item.hasPurchasePrice ? String(format: "%.2f", item.purchasePrice) : ""
                replacement = item.hasReplacementValue ? String(format: "%.2f", item.replacementValue) : ""
                asin = item.hasOverrideAsin ? item.overrideAsin : ""
            }
        }
    }

    private func save() {
        saving = true
        Task {
            do {
                try await authManager.grpcClient.adminUpdateMediaItem(
                    id: item.mediaItemID,
                    place: place.isEmpty ? nil : place,
                    date: date.isEmpty ? nil : date,
                    price: Double(price),
                    replacementValue: Double(replacement),
                    asin: asin.isEmpty ? nil : asin
                )
                onSave()
                dismiss()
            } catch {}
            saving = false
        }
    }
}
