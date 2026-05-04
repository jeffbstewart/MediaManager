import SwiftUI
import WebKit

private let log = MMLogger(category: "BookReaderView")

/// Navigation route into the ebook reader. Carries the
/// `media_item_id` that keys both the download
/// (`DownloadService.DownloadBookFile`) and the reading-progress
/// stream (`PlaybackService.ReportReadingProgress`).
struct BookReaderRoute: Hashable {
    let mediaItemId: Int64
    let titleName: String
}

/// Ebook reader (v1: EPUB only). The WKWebView hosts a bundled
/// `reader.html` that wraps epub.js; native chrome owns close /
/// font controls / progress percent. The reader's font-size
/// controls intentionally mirror the web fix from commit 22add35
/// — see the matching theme registration in `reader.html`.
///
/// Lifecycle:
///
///  1. `task` kicks off `loadAndOpen()`: stages bundled JS/HTML
///     into the shared reader cache once per app launch, downloads
///     the EPUB to `book-{mediaItemId}.epub` if missing, fetches
///     the user's last reading progress.
///  2. WKWebView loads `reader.html` and we send `MMReader.boot(...)`
///     once the page is ready.
///  3. JS sends `relocated` messages on every page change; we keep
///     the latest CFI in `lastCfi` and the percent in `progressPct`.
///  4. A 10 s timer reports the latest position via
///     `ReportReadingProgress`. A final report fires from
///     `cleanup()` so closing the page captures the tail.
struct BookReaderView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.dismiss) private var dismiss
    let route: BookReaderRoute

    @State private var status: Status = .loading("Preparing book…")
    @State private var fontPct: Int = 100
    @State private var progressPct: Double = 0
    @State private var lastCfi: String? = nil
    @State private var bridge = ReaderBridge()
    @State private var reportTimer: Timer? = nil
    @State private var stagedEpubFile: URL? = nil
    @State private var stagedReaderHtml: URL? = nil
    @State private var theme: ReaderTheme = .stored
    @State private var showTOC = false
    @State private var toc: [TocEntry] = []

    enum Status: Equatable {
        case loading(String)
        case reading
        case error(String)
    }

    /// Reader theme. The choice is persisted via UserDefaults so the
    /// reader keeps the user's preferred mode across sessions /
    /// books. The names match the registered themes in `reader.html`.
    enum ReaderTheme: String, CaseIterable {
        case light, sepia, dark
        var icon: String {
            switch self {
            case .light: return "sun.max"
            case .sepia: return "book.closed"
            case .dark: return "moon"
            }
        }
        var next: ReaderTheme {
            switch self {
            case .light: return .sepia
            case .sepia: return .dark
            case .dark: return .light
            }
        }
        /// Background colour that matches the WKWebView paint, used
        /// to tint the SwiftUI nav bar so the reader feels like one
        /// surface. RGB values mirror the constants in
        /// `reader.html`'s THEMES table.
        var background: Color {
            switch self {
            case .light: return Color(red: 1.0, green: 1.0, blue: 1.0)
            case .sepia: return Color(red: 244/255, green: 236/255, blue: 216/255)
            case .dark:  return Color(red: 26/255, green: 26/255, blue: 26/255)
            }
        }
        /// Foreground colour for the nav-bar title + buttons. Same
        /// values as the reader's body `color`.
        var foreground: Color {
            switch self {
            case .light: return Color(red: 26/255, green: 26/255, blue: 26/255)
            case .sepia: return Color(red: 61/255, green: 47/255, blue: 32/255)
            case .dark:  return Color(red: 230/255, green: 230/255, blue: 230/255)
            }
        }
        static var stored: ReaderTheme {
            ReaderTheme(rawValue: UserDefaults.standard.string(forKey: "readerTheme") ?? "") ?? .light
        }
        func persist() {
            UserDefaults.standard.set(rawValue, forKey: "readerTheme")
        }
    }

    struct TocEntry: Identifiable {
        let id: Int
        let label: String
        let href: String
        let depth: Int
    }

    var body: some View {
        ZStack {
            // The web view is always mounted once we have files staged
            // — switching it in/out of the hierarchy on state change
            // would tear down the JS bridge mid-load. The loading and
            // error overlays sit on top of it.
            if let html = stagedReaderHtml, let epub = stagedEpubFile {
                ReaderWebView(
                    htmlURL: html,
                    epubFileName: epub.lastPathComponent,
                    fontPct: fontPct,
                    bridge: bridge)
                    .ignoresSafeArea(.container, edges: .bottom)
            } else {
                Color.white.ignoresSafeArea()
            }

            switch status {
            case .loading(let msg):
                VStack(spacing: 12) {
                    ProgressView().controlSize(.large)
                    Text(msg).foregroundStyle(.secondary)
                }
                .padding(24)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))

            case .error(let msg):
                VStack(spacing: 16) {
                    ContentUnavailableView(msg, systemImage: "exclamationmark.triangle")
                    Button("Close") { cleanup(); dismiss() }
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))

            case .reading:
                EmptyView()
            }
        }
        .navigationTitle(route.titleName)
        .navigationBarTitleDisplayMode(.inline)
        // Tint the navigation bar to match the reader theme so the
        // top chrome doesn't read as a separate layer floating over
        // the page. The toolbar tint colour drives back chevron +
        // toolbar icons; the foreground titlebar colour scopes only
        // to this view's nav bar (we set `for: .navigationBar`).
        .toolbarBackground(theme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(theme == .dark ? .dark : .light, for: .navigationBar)
        .tint(theme.foreground)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 16) {
                    if status == .reading {
                        Text("\(Int(progressPct * 100))%")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Button { Task { await loadTOCAndShow() } } label: {
                        Image(systemName: "list.bullet")
                    }
                    .disabled(status != .reading)
                    Button { cycleTheme() } label: { Image(systemName: theme.icon) }
                        .disabled(status != .reading)
                    Button { decreaseFont() } label: { Image(systemName: "textformat.size.smaller") }
                        .disabled(status != .reading || fontPct <= 60)
                    Button { increaseFont() } label: { Image(systemName: "textformat.size.larger") }
                        .disabled(status != .reading || fontPct >= 200)
                }
            }
        }
        .sheet(isPresented: $showTOC) {
            TOCSheet(entries: toc) { entry in
                bridge.runJS("window.MMReader.gotoHref('\(entry.href.replacingOccurrences(of: "'", with: "\\'"))')")
                showTOC = false
            }
            .presentationDetents([.medium, .large])
        }
        .task { await loadAndOpen() }
        .onDisappear { cleanup() }
    }

    // MARK: - TOC + theme

    private func cycleTheme() {
        theme = theme.next
        theme.persist()
        bridge.runJS("window.MMReader.setTheme('\(theme.rawValue)')")
        // Theme switch resets epub.js's body-level font-size — re-apply
        // ours so the page doesn't snap back to 100%.
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    private func loadTOCAndShow() async {
        // Fetch the TOC each time the sheet opens. The list rarely
        // changes, but `getToc` is cheap (it walks an in-memory
        // structure inside epub.js) and avoids a stale snapshot if
        // the book is replaced.
        let entries = await bridge.evalToc("window.MMReader.getToc()")
        toc = entries.enumerated().map { idx, e in
            TocEntry(id: idx, label: e.label, href: e.href, depth: e.depth)
        }
        showTOC = true
    }

    // MARK: - Lifecycle

    private func loadAndOpen() async {
        do {
            status = .loading("Preparing reader…")
            let dir = try ReaderStaging.shared.ensureReaderDir()
            let htmlURL = dir.appendingPathComponent("reader.html")

            status = .loading("Downloading book…")
            let epubURL = try await ensureEpubDownloaded(into: dir)

            // Resume position before bootArgs land. Booting with a
            // `resumeCfi` tells epub.js where to render the first
            // page; without it we'd flash the cover before the first
            // relocation event lands.
            if let progress = await dataModel.readingProgress(mediaItemId: route.mediaItemId) {
                lastCfi = progress.locator.isEmpty ? nil : progress.locator
                progressPct = progress.fraction
            }

            // Latch the boot args + message handler BEFORE the WebView
            // mounts. The mount is triggered by setting
            // `stagedReaderHtml` / `stagedEpubFile` below; the HTML
            // loads in milliseconds (local file), so `didFinish`
            // would otherwise fire while `bootArgs` is still nil and
            // skip the boot call entirely.
            bridge.onMessage = handleBridgeMessage(_:)
            bridge.bootArgs = .init(
                epubFileName: epubURL.lastPathComponent,
                resumeCfi: lastCfi,
                fontPct: fontPct)

            status = .loading("Opening book…")
            stagedReaderHtml = htmlURL
            stagedEpubFile = epubURL
        } catch {
            log.error("loadAndOpen failed", error: error)
            status = .error("Couldn't open this book.")
        }
    }

    /// Re-applies persisted preferences (theme + font) once the
    /// rendition reports ready. epub.js's `themes.select(...)` resets
    /// the body-level font-size to the new theme's default, so the
    /// font-size has to be re-pushed after every theme change too —
    /// `cycleTheme` makes the same pair of calls for the same reason.
    private func handleReadyApplyPrefs() {
        bridge.runJS("window.MMReader.setTheme('\(theme.rawValue)')")
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    private func ensureEpubDownloaded(into dir: URL) async throws -> URL {
        let dest = dir.appendingPathComponent("book-\(route.mediaItemId).epub")
        if FileManager.default.fileExists(atPath: dest.path) {
            log.info("EPUB already cached at \(dest.lastPathComponent)")
            return dest
        }

        // Stream the bytes straight to disk via FileHandle. Chunks
        // arrive in offset order from the server — a single
        // sequential write per chunk keeps memory use flat regardless
        // of book size.
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        let handle = try FileHandle(forWritingTo: dest)
        defer { try? handle.close() }

        try await dataModel.grpcClient.downloadBookFile(mediaItemId: route.mediaItemId) { chunk in
            do {
                try handle.write(contentsOf: chunk.data)
            } catch {
                log.error("EPUB chunk write failed at offset \(chunk.offset)", error: error)
            }
        }
        log.info("EPUB downloaded \(dest.lastPathComponent) (\(FileManager.default.attributes(at: dest)?[.size] ?? 0) bytes)")
        return dest
    }

    private func handleBridgeMessage(_ msg: ReaderBridge.Message) {
        switch msg {
        case .ready:
            status = .reading
            handleReadyApplyPrefs()
            startReportTimer()
        case .relocated(let cfi, let pct):
            lastCfi = cfi
            progressPct = pct
        case .error(let m):
            log.warning("reader.html reported error: \(m)")
            status = .error(m)
        }
    }

    private func startReportTimer() {
        reportTimer?.invalidate()
        // 10 s mirrors the web reader's PROGRESS_REPORT_MS. Tight enough
        // that closing the app loses at most ten seconds of progress;
        // loose enough that we're not hammering the server while the
        // user reads.
        reportTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { _ in
            Task { @MainActor in await reportProgress() }
        }
    }

    private func reportProgress() async {
        guard let cfi = lastCfi else { return }
        await dataModel.reportReadingProgress(
            mediaItemId: route.mediaItemId,
            locator: cfi,
            fraction: progressPct)
    }

    private func increaseFont() {
        fontPct = min(200, fontPct + 10)
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    private func decreaseFont() {
        fontPct = max(60, fontPct - 10)
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    private func cleanup() {
        reportTimer?.invalidate()
        reportTimer = nil
        // Final report so the tail of progress isn't lost between the
        // last timer tick and dismissal.
        if lastCfi != nil {
            Task { @MainActor in await reportProgress() }
        }
    }
}

// MARK: - Web view wrapper

/// Hosts the WKWebView that runs `reader.html`. The view is recreated
/// when `htmlURL` or `epubFileName` change (rare; both come from
/// `loadAndOpen` once), but `setFont` and similar updates flow through
/// the long-lived `bridge` without needing to rebuild the underlying
/// WKWebView.
private struct ReaderWebView: UIViewRepresentable {
    let htmlURL: URL
    let epubFileName: String
    let fontPct: Int
    let bridge: ReaderBridge

    func makeCoordinator() -> Coordinator { Coordinator(bridge: bridge) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // The page sends events to the Swift side via
        // `window.webkit.messageHandlers.reader.postMessage(...)`.
        config.userContentController.add(context.coordinator, name: "reader")
        // Disable zoom / link callouts so the reader feels like a
        // native page-turn surface, not a scrollable webview.
        config.allowsInlineMediaPlayback = true
        // epub.js fetches the EPUB binary via XHR. Without these
        // private preferences WKWebView blocks file://→file:// XHR
        // (even when the directory is in `allowingReadAccessTo`),
        // which manifests as `rendition.display()` hanging
        // forever — the request never resolves, no error fires.
        // Both keys are documented in WebKit headers but only
        // exposed via `setValue(_:forKey:)`.
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        config.setValue(true, forKey: "allowUniversalAccessFromFileURLs")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        // Hand the bridge a way to call back into the live web view
        // (font changes, page flips). The reference is weak inside
        // ReaderBridge so a dismiss doesn't leak.
        bridge.attach(webView: webView)
        // Allow the page to read the staged epub from the same dir.
        webView.loadFileURL(htmlURL, allowingReadAccessTo: htmlURL.deletingLastPathComponent())
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // No-op — bridge.runJS handles in-place updates so we don't
        // re-load the page on every font change.
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "reader")
        uiView.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let bridge: ReaderBridge

        init(bridge: ReaderBridge) {
            self.bridge = bridge
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Boot the reader the moment the bundled HTML page finishes
            // loading. Args were latched in BookReaderView before the
            // web view mounted — see `bridge.bootArgs`.
            guard let args = bridge.bootArgs else { return }
            let escapedFile = args.epubFileName.replacingOccurrences(of: "'", with: "\\'")
            let cfiArg: String
            if let cfi = args.resumeCfi, !cfi.isEmpty {
                let escaped = cfi
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: "'", with: "\\'")
                cfiArg = "'\(escaped)'"
            } else {
                cfiArg = "null"
            }
            // `MMReader.boot` is an async function — it returns a
            // Promise. WKWebView's evaluateJavaScript can't serialise
            // that and reports it as an "unsupported type" error in
            // the completion handler, even though the script itself
            // ran fine. Returning a plain `null` from the wrapped
            // expression sidesteps the warning. JS runtime / load
            // failures still surface via the script's own
            // postNative({ type: 'error' }) path.
            let js = "window.MMReader.boot('\(escapedFile)', \(cfiArg), \(args.fontPct)); null;"
            webView.evaluateJavaScript(js, completionHandler: nil)
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any] else { return }
            guard let type = body["type"] as? String else { return }
            let parsed: ReaderBridge.Message?
            switch type {
            case "ready":
                parsed = .ready
            case "relocated":
                // Hot path — fires on every page flip. No log line.
                parsed = .relocated(
                    cfi: (body["cfi"] as? String) ?? "",
                    percent: (body["percent"] as? Double) ?? 0)
            case "error":
                let m = (body["message"] as? String) ?? "Unknown reader error"
                log.warning("reader.html error: \(m)")
                parsed = .error(m)
            default:
                parsed = nil
            }
            if let parsed {
                Task { @MainActor in self.bridge.onMessage?(parsed) }
            }
        }
    }
}

/// Owns the live WKWebView reference and the boot-args payload, so
/// SwiftUI re-renders of `BookReaderView` don't tear down the bridge.
@MainActor
@Observable
final class ReaderBridge {
    struct BootArgs: Equatable {
        let epubFileName: String
        let resumeCfi: String?
        let fontPct: Int
    }
    enum Message {
        case ready
        case relocated(cfi: String, percent: Double)
        case error(String)
    }

    var onMessage: ((Message) -> Void)?
    var bootArgs: BootArgs?
    private weak var webView: WKWebView?

    func attach(webView: WKWebView) {
        self.webView = webView
    }

    func runJS(_ script: String) {
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }

    /// Like `runJS` but returns the script's result. Used for getToc
    /// where we need the array back. epub.js's getToc is synchronous
    /// (walks an in-memory structure), so the JS expression evaluates
    /// to a serialisable array of dictionaries — no Promise warning.
    /// Fetches the TOC from epub.js. The conversion from JS-bridged
    /// `[[String: Any]]` (which isn't `Sendable`) to a typed
    /// `[TocRecord]` happens inside the WKWebView callback so only
    /// `Sendable` values cross the continuation. Without this the
    /// Swift 6 strict-sending checker rejects the bridge call.
    func evalToc(_ script: String) async -> [TocRecord] {
        guard let webView else { return [] }
        return await withCheckedContinuation { (cont: CheckedContinuation<[TocRecord], Never>) in
            webView.evaluateJavaScript(script) { result, _ in
                let raw = (result as? [[String: Any]]) ?? []
                let parsed: [TocRecord] = raw.map { e in
                    TocRecord(
                        label: (e["label"] as? String) ?? "",
                        href: (e["href"] as? String) ?? "",
                        depth: (e["depth"] as? Int) ?? 0)
                }
                cont.resume(returning: parsed)
            }
        }
    }
}

/// Sendable mirror of `BookReaderView.TocEntry` used to ferry results
/// across the WKWebView completion-handler continuation.
struct TocRecord: Sendable {
    let label: String
    let href: String
    let depth: Int
}

/// Chapter list sheet shown from the reader's toolbar. The TOC is
/// fetched fresh each open via `MMReader.getToc()` rather than
/// cached on the Swift side, so a reload of the same book on a
/// different device picks up the latest spine without manual
/// invalidation.
private struct TOCSheet: View {
    let entries: [BookReaderView.TocEntry]
    let onTap: (BookReaderView.TocEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if entries.isEmpty {
                    ContentUnavailableView("No chapters", systemImage: "list.bullet",
                        description: Text("This book doesn't expose a table of contents."))
                } else {
                    List(entries) { e in
                        Button {
                            onTap(e)
                        } label: {
                            // Indent by depth to convey nesting; epub.js
                            // hands us a flat list with a depth marker.
                            HStack {
                                if e.depth > 0 {
                                    Spacer().frame(width: CGFloat(e.depth) * 16)
                                }
                                Text(e.label.isEmpty ? "Untitled" : e.label)
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Staging

/// Stages the bundled reader assets into the OS cache directory once
/// per app launch. WKWebView's `loadFileURL(allowingReadAccessTo:)`
/// requires a real on-disk path, and the bundle on a device is
/// read-only — so we copy the assets into the writable cache and
/// keep the downloaded EPUBs as siblings.
private final class ReaderStaging: @unchecked Sendable {
    static let shared = ReaderStaging()

    private var staged = false

    func ensureReaderDir() throws -> URL {
        let dir = try FileManager.default
            .url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("reader", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        if !staged {
            try copyBundledAsset("reader", "html", into: dir)
            try copyBundledAsset("epub.min", "js", into: dir)
            try copyBundledAsset("jszip.min", "js", into: dir)
            staged = true
        }
        return dir
    }

    private func copyBundledAsset(_ name: String, _ ext: String, into dir: URL) throws {
        guard let src = Bundle.main.url(forResource: name, withExtension: ext) else {
            throw NSError(domain: "MediaManager", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Missing bundled reader asset \(name).\(ext)"])
        }
        let dst = dir.appendingPathComponent(src.lastPathComponent)
        // Always overwrite so a build with updated reader.html /
        // epub.min.js takes effect on the next launch instead of
        // sticking with the previous staged copy.
        if FileManager.default.fileExists(atPath: dst.path) {
            try FileManager.default.removeItem(at: dst)
        }
        try FileManager.default.copyItem(at: src, to: dst)
    }
}

private extension FileManager {
    func attributes(at url: URL) -> [FileAttributeKey: Any]? {
        try? attributesOfItem(atPath: url.path)
    }
}
