import SwiftUI

struct ScanDetailView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let scanId: Int64

    @State private var detail: MMScanDetailResponse?
    @State private var loading = true
    @State private var showTmdbSearch = false
    @State private var showCamera = false
    @State private var purchasePlace = ""
    @State private var purchaseDate: Date?
    @State private var purchasePrice = ""
    @State private var statusMessage: String?
    @State private var statusIsError = false
    @State private var saveTask: Task<Void, Never>?
    @State private var monitorTask: Task<Void, Never>?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                List {
                    // Header
                    Section {
                        HStack(spacing: 16) {
                            if detail.hasPosterURL {
                                AuthenticatedImage(path: detail.posterURL, apiClient: dataModel.apiClient, cornerRadius: 8)
                                    .frame(width: 80, height: 120)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                if detail.hasTitleName {
                                    Text(detail.titleName)
                                        .font(.headline)
                                }
                                if detail.hasProductName && detail.productName != detail.titleName {
                                    Text(detail.productName)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text(detail.upc)
                                    .font(.caption)
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                                if detail.hasReleaseYear {
                                    Text(String(detail.releaseYear))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                StatusBadge(status: detail.status)
                            }
                        }
                    }

                    // TMDB Match section (if action needed)
                    if detail.status.needsTmdbAction {
                        Section("TMDB Match") {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(detail.enrichmentStatus == .skipped ? "No TMDB match found" : "Enrichment failed")
                                    .foregroundStyle(.orange)

                                Button {
                                    showTmdbSearch = true
                                } label: {
                                    Label("Search TMDB", systemImage: "magnifyingglass")
                                }
                            }
                        }
                    }

                    // Purchase Info
                    Section("Purchase Info") {
                        TextField("Where purchased", text: $purchasePlace)
                            .onChange(of: purchasePlace) { debounceSave() }

                        DatePicker("Date", selection: Binding(
                            get: { purchaseDate ?? Date() },
                            set: { purchaseDate = $0; debounceSave() }
                        ), displayedComponents: .date)

                        HStack {
                            Text("$")
                            TextField("Price", text: $purchasePrice)
                                .keyboardType(.decimalPad)
                                .onChange(of: purchasePrice) { debounceSave() }
                        }

                        if purchasePlace.isEmpty && purchaseDate == nil && purchasePrice.isEmpty {
                            Text("No purchase info yet")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    // Ownership Photos
                    Section("Photos") {
                        if !detail.photos.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(detail.photos, id: \.photoID) { photo in
                                        PhotoThumbnail(photo: photo, apiClient: dataModel.apiClient) {
                                            deletePhoto(photoId: photo.photoID)
                                        }
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }

                        Button {
                            showCamera = true
                        } label: {
                            Label("Take Photo", systemImage: "camera")
                        }
                    }

                    if let statusMessage {
                        Section {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundStyle(statusIsError ? .red : .green)
                        }
                    }
                }
            } else {
                ContentUnavailableView("Not Found", systemImage: "barcode")
            }
        }
        .navigationTitle("Scan Detail")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
            startMonitor()
        }
        .onDisappear {
            monitorTask?.cancel()
            monitorTask = nil
        }
        .refreshable { await loadDetail() }
        .sheet(isPresented: $showTmdbSearch) {
            TmdbSearchSheet(
                initialQuery: detail?.hasTitleName == true ? detail!.titleName : (detail?.hasProductName == true ? detail!.productName : ""),
                titleId: detail?.hasTitleID == true ? detail!.titleID : 0
            ) {
                // On TMDB assigned — reload detail
                Task { await loadDetail() }
            }
        }
        .sheet(isPresented: $showCamera) {
            PhotoCaptureView { imageData in
                showCamera = false
                Task { await uploadPhoto(data: imageData) }
            } onDismiss: {
                showCamera = false
            }
        }
    }

    // MARK: - Live Monitor

    private func startMonitor() {
        monitorTask = Task {
            do {
                try await dataModel.monitorScanProgress { update in
                    await MainActor.run {
                        // Only react to events for this scan
                        if case .event(let event) = update.update, event.scanID == scanId {
                            // Reload full detail to get updated poster, title, enrichment status
                            Task { await loadDetail() }
                        }
                    }
                }
            } catch is CancellationError {
                // Normal
            } catch {
                // Stream failed — not critical, user can pull to refresh
            }
        }
    }

    // MARK: - Data Loading

    private func loadDetail() async {
        do {
            let response = try await dataModel.getScanDetail(scanId: scanId)
            detail = response
            purchasePlace = response.hasPurchasePlace ? response.purchasePlace : ""
            if response.hasPurchaseDate {
                let cal = response.purchaseDate
                purchaseDate = Calendar.current.date(from: DateComponents(year: Int(cal.year), month: Int(cal.month.rawValue), day: Int(cal.day)))
            } else {
                purchaseDate = nil
            }
            purchasePrice = response.hasPurchasePrice ? String(format: "%.2f", response.purchasePrice) : ""
            loading = false
        } catch {
            loading = false
        }
    }

    // MARK: - Purchase Info

    private func debounceSave() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            savePurchaseInfo()
        }
    }

    private func savePurchaseInfo() {
        Task {
            var calDate: MMCalendarDate?
            if let purchaseDate {
                let components = Calendar.current.dateComponents([.year, .month, .day], from: purchaseDate)
                var d = MMCalendarDate()
                d.year = Int32(components.year ?? 2025)
                d.month = MMMonth(rawValue: components.month ?? 1) ?? .unknown
                d.day = Int32(components.day ?? 1)
                calDate = d
            }
            let price = Double(purchasePrice)
            try? await dataModel.updatePurchaseInfo(
                scanId: scanId,
                place: purchasePlace.isEmpty ? nil : purchasePlace,
                date: calDate,
                price: price
            )
        }
    }

    // MARK: - Photos

    private func uploadPhoto(data: Data) async {
        let sizeMB = String(format: "%.1f", Double(data.count) / 1_048_576.0)
        do {
            _ = try await dataModel.uploadOwnershipPhoto(scanId: scanId, photoData: data, contentType: "image/jpeg")
            statusMessage = "Photo saved (\(sizeMB) MB)"
            statusIsError = false
            await loadDetail()
        } catch {
            statusMessage = "Photo upload failed (\(sizeMB) MB): \(error.localizedDescription)"
            statusIsError = true
        }
    }

    private func deletePhoto(photoId: String) {
        Task {
            try? await dataModel.deleteOwnershipPhoto(photoId: photoId)
            await loadDetail()
        }
    }
}

// MARK: - Photo Thumbnail

struct PhotoThumbnail: View {
    let photo: MMOwnershipPhotoInfo
    let apiClient: APIClient
    let onDelete: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            AuthenticatedImage(path: photo.url, apiClient: apiClient, cornerRadius: 6, contentMode: .fill)
                .frame(width: 80, height: 80)
                .clipped()

            Button(role: .destructive) {
                onDelete()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.white, .red)
            }
            .offset(x: 4, y: -4)
        }
    }
}
