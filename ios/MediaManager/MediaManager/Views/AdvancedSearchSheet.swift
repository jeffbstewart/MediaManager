import SwiftUI

private let log = MMLogger(category: "AdvancedSearchSheet")

/// Modal sheet that composes an `AdvancedTrackSearchFilters`. Mirrors
/// the web app's advanced-search dialog: server-curated dance preset
/// chips that pre-fill the form, BPM min/max, time-signature picker,
/// optional free-text query. Submit hands the filter record to the
/// caller via the `onSubmit` closure — this view doesn't navigate.
struct AdvancedSearchSheet: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss

    /// Caller-supplied submission handler. Runs after the sheet
    /// dismisses so the receiving view can push a results destination
    /// without fighting the modal animation.
    let onSubmit: (AdvancedTrackSearchFilters) -> Void

    @State private var presets: [ApiAdvancedSearchPreset] = []
    @State private var loadingPresets = true
    @State private var activePresetKey: String? = nil

    @State private var query: String = ""
    @State private var bpmMinText: String = ""
    @State private var bpmMaxText: String = ""
    @State private var timeSignature: String = ""

    /// Time-signature dropdown values. "" → "Any" (don't restrict).
    /// Same set the web client offers; expand if a preset references
    /// something else.
    private let timeSigOptions: [(value: String, label: String)] = [
        ("",    "Any"),
        ("3/4", "3/4 (waltz)"),
        ("4/4", "4/4"),
        ("6/8", "6/8"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                if loadingPresets {
                    Section {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                    }
                } else if !presets.isEmpty {
                    presetsSection
                }
                filtersSection
            }
            .navigationTitle("Advanced Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Search") { submit() }
                        .disabled(submitDisabled)
                }
            }
            .task {
                do {
                    presets = try await dataModel.advancedSearchPresets()
                } catch {
                    log.warning("advancedSearchPresets failed: \(error.localizedDescription)")
                }
                loadingPresets = false
            }
        }
    }

    private var presetsSection: some View {
        Section {
            // WrappingHStack flows the chips onto multiple rows so
            // the full list (15 dances today) sits visible at once.
            // A horizontal ScrollView buries half the catalog
            // off-screen even though there's plenty of room — this
            // matches the web client's wrap-on-overflow chip rail.
            WrappingHStack(spacing: 8, lineSpacing: 8) {
                ForEach(presets) { preset in
                    presetChip(preset)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 4)
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
        } header: {
            Text("Dance Presets")
        } footer: {
            // Show the active preset's description as a help line so
            // the user understands what tempo they just selected.
            if let active = activePreset {
                Text(active.description)
                    .font(.caption)
            }
        }
    }

    @ViewBuilder
    private func presetChip(_ preset: ApiAdvancedSearchPreset) -> some View {
        let isActive = activePresetKey == preset.key
        Button {
            applyPreset(preset)
        } label: {
            Text(preset.name)
                .font(.subheadline.weight(isActive ? .semibold : .regular))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isActive ? Color.accentColor : Color.gray.opacity(0.15))
                .foregroundStyle(isActive ? Color.white : Color.primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var filtersSection: some View {
        Section("Filters") {
            HStack {
                Text("Search")
                    .frame(width: 80, alignment: .leading)
                TextField("Title, album, artist", text: $query)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: query) { _, _ in activePresetKey = nil }
            }
            HStack {
                Text("BPM")
                    .frame(width: 80, alignment: .leading)
                TextField("Min", text: $bpmMinText)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 80)
                    .onChange(of: bpmMinText) { _, _ in activePresetKey = nil }
                Text("–").foregroundStyle(.secondary)
                TextField("Max", text: $bpmMaxText)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 80)
                    .onChange(of: bpmMaxText) { _, _ in activePresetKey = nil }
                Spacer()
            }
            Picker("Time Signature", selection: $timeSignature) {
                ForEach(timeSigOptions, id: \.value) { option in
                    Text(option.label).tag(option.value)
                }
            }
            .onChange(of: timeSignature) { _, _ in activePresetKey = nil }
            if anyFilterSet {
                Button("Clear all", role: .destructive) {
                    query = ""
                    bpmMinText = ""
                    bpmMaxText = ""
                    timeSignature = ""
                    activePresetKey = nil
                }
            }
        }
    }

    // MARK: - State helpers

    private var activePreset: ApiAdvancedSearchPreset? {
        guard let key = activePresetKey else { return nil }
        return presets.first { $0.key == key }
    }

    private var anyFilterSet: Bool {
        !query.isEmpty || !bpmMinText.isEmpty || !bpmMaxText.isEmpty || !timeSignature.isEmpty
    }

    /// Mirror of the web client's submit guard: an all-empty filter
    /// set returns nothing, so block submit until something is set.
    private var submitDisabled: Bool { !anyFilterSet }

    private func applyPreset(_ preset: ApiAdvancedSearchPreset) {
        bpmMinText = preset.bpmMin.map(String.init) ?? ""
        bpmMaxText = preset.bpmMax.map(String.init) ?? ""
        timeSignature = preset.timeSignature ?? ""
        activePresetKey = preset.key
        // Don't auto-submit — user can still type a query name or
        // tighten the BPM range before tapping Search.
    }

    private func submit() {
        var filters = AdvancedTrackSearchFilters()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { filters.query = trimmed }
        if let m = Int(bpmMinText), m > 0 { filters.bpmMin = m }
        if let m = Int(bpmMaxText), m > 0 { filters.bpmMax = m }
        if !timeSignature.isEmpty { filters.timeSignature = timeSignature }
        guard !filters.isEmpty else { return }
        dismiss()
        onSubmit(filters)
    }
}

/// Greedy wrapping HStack — places children left-to-right and breaks
/// to a new row when the next child won't fit. Used here for the
/// dance-preset chips so the whole catalog sits visible at once
/// instead of scrolling sideways. Sized variable-width per child so
/// short names ("Jive") and long ones ("West Coast Swing") pack
/// efficiently.
struct WrappingHStack: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let layout = compute(width: width, subviews: subviews)
        // Report the height we used; width tracks the proposal so
        // SwiftUI doesn't try to shrink-wrap us narrower than the
        // available container.
        return CGSize(width: width.isFinite ? width : layout.usedWidth, height: layout.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = compute(width: bounds.width, subviews: subviews)
        for (index, frame) in layout.frames.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(width: frame.width, height: frame.height))
        }
    }

    private func compute(width: CGFloat, subviews: Subviews) -> (frames: [CGRect], height: CGFloat, usedWidth: CGFloat) {
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var usedWidth: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            // Wrap when the child would overflow — but never on the
            // first child of a row (a single chip wider than the
            // container still has to render somewhere).
            if x > 0, x + size.width > width {
                y += lineHeight + lineSpacing
                x = 0
                lineHeight = 0
            }
            frames.append(CGRect(x: x, y: y, width: size.width, height: size.height))
            x += size.width + spacing
            usedWidth = max(usedWidth, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        return (frames, y + lineHeight, usedWidth)
    }
}
