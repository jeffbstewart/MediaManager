import SwiftUI

struct BarcodeScanView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @State private var upcText = ""
    @State private var recentScans: [ScanItem] = []
    @State private var loading = true
    @State private var showCamera = false
    @State private var statusMessage: String?
    @State private var statusIsError = false
    @State private var monitorTask: Task<Void, Never>?

    // Bluetooth scanner detection
    @State private var inputStartTime: Date?

    var body: some View {
        List {
            // Input section
            Section("Scan Barcode") {
                HStack(spacing: 12) {
                    TextField("UPC", text: $upcText)
                        .keyboardType(.numberPad)
                        .textContentType(.none)
                        .autocorrectionDisabled()
                        .onChange(of: upcText) { oldValue, newValue in
                            detectScannerInput(old: oldValue, new: newValue)
                        }
                        .onSubmit { submitBarcode() }

                    Button {
                        submitBarcode()
                    } label: {
                        Image(systemName: "arrow.right.circle.fill")
                            .font(.title2)
                    }
                    .disabled(upcText.trimmingCharacters(in: .whitespaces).isEmpty)

                    if BarcodeScannerView.isAvailable {
                        Button {
                            showCamera = true
                        } label: {
                            Image(systemName: "camera.fill")
                                .font(.title2)
                        }
                    }
                }

                if let statusMessage {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundStyle(statusIsError ? .red : .green)
                }
            }

            // Recent scans with live status
            if !recentScans.isEmpty {
                Section("Recent Scans") {
                    ForEach(recentScans) { scan in
                        NavigationLink(value: scan.id) {
                            ScanRow(scan: scan, apiClient: dataModel.apiClient)
                        }
                    }
                }
            } else if !loading {
                Section("Recent Scans") {
                    Text("No scans yet")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Scan")
        .task {
            startMonitor()
        }
        .onDisappear {
            monitorTask?.cancel()
            monitorTask = nil
        }
        .refreshable {
            monitorTask?.cancel()
            monitorTask = nil
            startMonitor()
        }
        .navigationDestination(for: Int64.self) { scanId in
            ScanDetailView(scanId: scanId)
        }
        .sheet(isPresented: $showCamera) {
            NavigationStack {
                BarcodeScannerView(
                    onBarcodeScanned: { barcode in
                        upcText = barcode
                        submitBarcode()
                    },
                    onDismiss: { showCamera = false }
                )
                .navigationTitle("Scan Barcode")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showCamera = false }
                    }
                }
            }
        }
    }

    // MARK: - Actions

    private func submitBarcode() {
        let upc = upcText.trimmingCharacters(in: .whitespaces)
        guard !upc.isEmpty else { return }
        upcText = ""
        inputStartTime = nil

        Task {
            do {
                let response = try await dataModel.submitBarcode(upc: upc)
                switch response.result {
                case .created:
                    showStatus(response.hasMessage ? response.message : "Scanned: \(upc)", isError: false)
                case .duplicate:
                    showStatus(response.hasMessage ? response.message : "Already scanned", isError: false)
                case .invalid:
                    showStatus(response.hasMessage ? response.message : "Invalid UPC", isError: true)
                default:
                    showStatus("Unexpected response", isError: true)
                }
            } catch {
                showStatus("Failed: \(error.localizedDescription)", isError: true)
            }
        }
    }

    private func showStatus(_ message: String, isError: Bool) {
        statusMessage = message
        statusIsError = isError
        Task {
            try? await Task.sleep(for: .seconds(5))
            if statusMessage == message {
                statusMessage = nil
            }
        }
    }

    // MARK: - Bluetooth Scanner Detection

    private func detectScannerInput(old: String, new: String) {
        // Track when input starts
        if old.isEmpty && !new.isEmpty {
            inputStartTime = Date()
        }

        // If 8+ digits arrived within 500ms, it's a scanner — auto-submit
        guard let start = inputStartTime else { return }
        let digits = new.filter(\.isNumber)
        if digits.count >= 8 && Date().timeIntervalSince(start) < 0.5 {
            // Scanner detected — submit on next run loop to let text field settle
            Task { @MainActor in
                submitBarcode()
            }
        }
    }

    // MARK: - Streaming Monitor

    private func startMonitor() {
        monitorTask = Task {
            do {
                try await dataModel.monitorScanProgress { update in
                    await MainActor.run {
                        handleUpdate(update)
                    }
                }
            } catch is CancellationError {
                // Normal
            } catch {
                // Fallback — just mark as loaded
                await MainActor.run { loading = false }
            }
        }
    }

    private func handleUpdate(_ update: MMScanProgressUpdate) {
        switch update.update {
        case .snapshot(let snapshot):
            recentScans = snapshot.scans.map { ScanItem(proto: $0) }
            loading = false
        case .event(let event):
            applyEvent(event)
        case nil:
            break
        }
    }

    private func applyEvent(_ event: MMScanProgressEvent) {
        if let idx = recentScans.firstIndex(where: { $0.id == event.scanID }) {
            recentScans[idx].status = event.status
            if event.hasTitleName { recentScans[idx].titleName = event.titleName }
            if event.hasPosterURL { recentScans[idx].posterUrl = event.posterURL }
            if event.hasTitleID { recentScans[idx].titleId = event.titleID }
        } else {
            // New scan — add to top
            recentScans.insert(ScanItem(
                id: event.scanID,
                upc: event.upc,
                status: event.status,
                titleName: event.hasTitleName ? event.titleName : nil,
                posterUrl: event.hasPosterURL ? event.posterURL : nil,
                titleId: event.hasTitleID ? event.titleID : nil,
                scannedAt: nil
            ), at: 0)
        }
    }
}

// MARK: - Scan Item Model

struct ScanItem: Identifiable {
    var id: Int64
    var upc: String
    var status: MMScanStatus
    var titleName: String?
    var posterUrl: String?
    var titleId: Int64?
    var scannedAt: String?

    init(proto: MMRecentScan) {
        self.id = proto.scanID
        self.upc = proto.upc
        self.status = proto.status
        self.titleName = proto.hasTitleName ? proto.titleName : nil
        self.posterUrl = proto.hasPosterURL ? proto.posterURL : nil
        self.titleId = proto.hasTitleID ? proto.titleID : nil
        self.scannedAt = proto.hasScannedAt ? proto.scannedAt.isoString : nil
    }

    init(id: Int64, upc: String, status: MMScanStatus, titleName: String?, posterUrl: String?, titleId: Int64?, scannedAt: String?) {
        self.id = id
        self.upc = upc
        self.status = status
        self.titleName = titleName
        self.posterUrl = posterUrl
        self.titleId = titleId
        self.scannedAt = scannedAt
    }
}

// MARK: - Scan Row

struct ScanRow: View {
    let scan: ScanItem
    let apiClient: APIClient

    var body: some View {
        HStack(spacing: 12) {
            // Poster thumbnail (if available)
            if scan.posterUrl != nil {
                AuthenticatedImage(path: scan.posterUrl, apiClient: apiClient, cornerRadius: 4)
                    .frame(width: 40, height: 56)
            }

            VStack(alignment: .leading, spacing: 4) {
                if let title = scan.titleName {
                    Text(title)
                        .font(.subheadline)
                        .lineLimit(1)
                }

                Text(scan.upc)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }

            Spacer()

            StatusBadge(status: scan.status)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Status Badge

struct StatusBadge: View {
    let status: MMScanStatus

    var body: some View {
        Text(status.displayString)
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(backgroundColor.opacity(0.15))
            .foregroundStyle(backgroundColor)
            .clipShape(Capsule())
    }

    private var backgroundColor: Color {
        switch status {
        case .submitted: .blue
        case .upcFound, .enriching: .orange
        case .enriched: .green
        case .upcNotFound, .enrichmentFailed: .red
        case .noMatch: .orange
        default: .gray
        }
    }
}
