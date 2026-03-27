import SwiftUI
import UniformTypeIdentifiers

struct AmazonImportView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var orders: [MMAmazonOrder] = []
    @State private var summary: MMAmazonOrderSummaryResponse?
    @State private var loading = true
    @State private var importing = false
    @State private var importMessage: String?
    @State private var searchQuery = ""
    @State private var mediaOnly = true
    @State private var unlinkedOnly = true
    @State private var hideCancelled = true
    @State private var showFilePicker = false

    var body: some View {
        List {
            if let summary {
                Section {
                    LabeledContent("Total Orders", value: "\(summary.total)")
                    LabeledContent("Media-Related", value: "\(summary.mediaRelated)")
                    LabeledContent("Linked to Catalog", value: "\(summary.linked)")
                }

                Section {
                    Button {
                        showFilePicker = true
                    } label: {
                        Label("Import CSV", systemImage: "square.and.arrow.down")
                    }
                    .disabled(importing)
                }
            }

            if let importMessage {
                Section {
                    Text(importMessage)
                        .font(.callout)
                        .foregroundStyle(.green)
                }
            }

            Section {
                TextField("Search orders", text: $searchQuery)
                    .autocorrectionDisabled()
                    .onSubmit { search() }
                Toggle("Media items only", isOn: $mediaOnly)
                    .onChange(of: mediaOnly) { _, _ in search() }
                Toggle("Unlinked only", isOn: $unlinkedOnly)
                    .onChange(of: unlinkedOnly) { _, _ in search() }
                Toggle("Hide cancelled", isOn: $hideCancelled)
                    .onChange(of: hideCancelled) { _, _ in search() }
            }

            if loading {
                Section { ProgressView("Loading...") }
            } else if orders.isEmpty {
                Section {
                    Text("No orders found")
                        .foregroundStyle(.secondary)
                }
            } else {
                Section("Orders (\(orders.count))") {
                    ForEach(orders, id: \.id) { order in
                        AmazonOrderRow(order: order) {
                            await unlinkOrder(order)
                        }
                    }
                }
            }
        }
        .navigationTitle("Amazon Import")
        .task { await loadData() }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.commaSeparatedText, .zip],
            allowsMultipleSelection: false
        ) { result in
            Task { await handleFileImport(result) }
        }
    }

    private func loadData() async {
        loading = true
        do {
            summary = try await authManager.grpcClient.adminGetAmazonOrderSummary()
            let response = try await authManager.grpcClient.adminSearchAmazonOrders(
                query: searchQuery, mediaOnly: mediaOnly,
                unlinkedOnly: unlinkedOnly, hideCancelled: hideCancelled)
            orders = response.orders
        } catch {
            importMessage = "Failed to load: \(error.localizedDescription)"
        }
        loading = false
    }

    private func search() {
        Task { await loadData() }
    }

    private func handleFileImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        importing = true
        Task {
            do {
                guard url.startAccessingSecurityScopedResource() else {
                    importMessage = "Cannot access file"
                    importing = false
                    return
                }
                defer { url.stopAccessingSecurityScopedResource() }

                let data = try Data(contentsOf: url)
                let response = try await authManager.grpcClient.adminImportAmazonOrders(
                    csvData: data, filename: url.lastPathComponent)

                if response.hasError {
                    importMessage = "Error: \(response.error)"
                } else {
                    importMessage = "Imported \(response.imported), skipped \(response.skipped)"
                }
                await loadData()
            } catch {
                importMessage = "Import failed: \(error.localizedDescription)"
            }
            importing = false
        }
    }

    private func unlinkOrder(_ order: MMAmazonOrder) async {
        guard order.hasLinkedMediaItemID else { return }
        do {
            try await authManager.grpcClient.adminUnlinkAmazonOrder(amazonOrderId: order.id)
            await loadData()
        } catch {
            importMessage = "Unlink failed"
        }
    }
}

struct AmazonOrderRow: View {
    let order: MMAmazonOrder
    let onUnlink: () async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(order.productName)
                .font(.subheadline)
                .lineLimit(2)

            HStack(spacing: 8) {
                if order.hasOrderDate {
                    Text(order.orderDate)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if order.hasUnitPrice {
                    Text(String(format: "$%.2f", order.unitPrice))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if order.hasProductCondition {
                    Text(order.productCondition)
                        .font(.caption2)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(.quaternary)
                        .clipShape(Capsule())
                }
            }

            if order.hasLinkedTitleName {
                HStack(spacing: 4) {
                    Image(systemName: "link")
                        .font(.caption2)
                    Text(order.linkedTitleName)
                        .font(.caption)
                        .foregroundStyle(.green)

                    Spacer()

                    Button("Unlink", role: .destructive) {
                        Task { await onUnlink() }
                    }
                    .font(.caption)
                }
            }
        }
        .padding(.vertical, 2)
    }
}
