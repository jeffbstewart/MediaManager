import SwiftUI

struct InventoryReportView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var report: MMInventoryReportResponse?
    @State private var loading = false
    @State private var csvURL: URL?
    @State private var showShare = false

    var body: some View {
        List {
            Section {
                Button {
                    Task { await generateReport() }
                } label: {
                    HStack {
                        Label("Generate Report", systemImage: "doc.text")
                        Spacer()
                        if loading { ProgressView() }
                    }
                }
                .disabled(loading)
            }

            if let report {
                Section("Summary") {
                    LabeledContent("Total Items", value: "\(report.totalItems)")
                    LabeledContent("Purchase Value", value: String(format: "$%.2f", report.totalPurchaseValue))
                    LabeledContent("Replacement Value", value: String(format: "$%.2f", report.totalReplacementValue))
                }

                Section {
                    Button {
                        exportCSV(report)
                    } label: {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                    }
                }

                Section("Items (\(report.rows.count))") {
                    ForEach(Array(report.rows.enumerated()), id: \.offset) { _, row in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(row.titleNames)
                                .font(.subheadline)
                                .lineLimit(2)
                            HStack(spacing: 8) {
                                Text(row.mediaFormat)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if row.hasPurchasePrice {
                                    Text(String(format: "$%.2f", row.purchasePrice))
                                        .font(.caption)
                                        .foregroundStyle(.green)
                                }
                                if row.hasReplacementValue {
                                    Text(String(format: "Repl: $%.2f", row.replacementValue))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if row.photoCount > 0 {
                                    Label("\(row.photoCount)", systemImage: "camera")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.vertical, 1)
                    }
                }
            }
        }
        .navigationTitle("Inventory Report")
        .sheet(isPresented: $showShare) {
            if let csvURL {
                ShareSheet(items: [csvURL])
            }
        }
    }

    private func generateReport() async {
        loading = true
        do {
            report = try await authManager.grpcClient.adminGenerateInventoryReport()
        } catch {}
        loading = false
    }

    private func exportCSV(_ report: MMInventoryReportResponse) {
        var csv = "Title(s),Format,UPC,Purchase Date,Purchase Place,Purchase Price,Replacement Value,Price Date,Photos\n"
        for row in report.rows {
            let title = row.titleNames.replacingOccurrences(of: "\"", with: "\"\"")
            let upc = row.hasUpc ? row.upc : ""
            let date = row.hasPurchaseDate ? row.purchaseDate : ""
            let place = row.hasPurchasePlace ? row.purchasePlace : ""
            let price = row.hasPurchasePrice ? String(format: "%.2f", row.purchasePrice) : ""
            let repl = row.hasReplacementValue ? String(format: "%.2f", row.replacementValue) : ""
            let replDate = row.hasReplacementValueDate ? row.replacementValueDate : ""
            csv += "\"\(title)\",\(row.mediaFormat),\(upc),\(date),\"\(place)\",\(price),\(repl),\(replDate),\(row.photoCount)\n"
        }

        let tempDir = FileManager.default.temporaryDirectory
        let file = tempDir.appendingPathComponent("inventory-\(Date().ISO8601Format()).csv")
        try? csv.write(to: file, atomically: true, encoding: .utf8)
        csvURL = file
        showShare = true
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
