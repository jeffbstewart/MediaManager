import SwiftUI
import WebKit

private let log = MMLogger(category: "BookReaderView")

/// Navigation route into the ebook reader. Carries the
/// `media_item_id` that keys both the download
/// (`DownloadService.DownloadBookFile`) and the reading-progress
/// stream (`PlaybackService.ReportReadingProgress`).
///
/// `testBundleEpub`: when non-nil, the reader skips the gRPC
/// download and progress-report paths and copies the named EPUB
/// from the app bundle (`Bundle.main.url(forResource:withExtension:)`)
/// into the staging dir. Used by the UI test target via the
/// `-MMReaderTestMode` launch arg; production navigation always
/// leaves this nil.
struct BookReaderRoute: Hashable {
    let mediaItemId: Int64
    let titleName: String
    var testBundleEpub: String? = nil
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
    @Environment(BookCacheManager.self) private var bookCache
    @Environment(ProgressFlusher.self) private var progressFlusher
    /// Local progress queue is a process-wide actor singleton — see
    /// the type's class doc for why it isn't injected via @Environment.
    private let progressQueue = ReadingProgressQueue.shared
    @Environment(\.dismiss) private var dismiss
    let route: BookReaderRoute

    @State private var status: Status = .loading("Preparing book…")
    /// Restored from UserDefaults so the reader keeps the user's font
    /// preference across sessions (issue #67). Clamped to the same
    /// 60-200 range increase/decreaseFont enforce, in case a stale
    /// value from a future version of the app ends up out of bounds.
    @State private var fontPct: Int = Self.storedFontPct
    @State private var progressPct: Double = 0
    @State private var lastCfi: String? = nil
    @State private var bridge = ReaderBridge()
    @State private var stagedEpubFile: URL? = nil
    @State private var stagedReaderHtml: URL? = nil
    @State private var theme: ReaderTheme = .stored
    @State private var showTOC = false
    @State private var toc: [TocEntry] = []
    /// Non-nil when we have a resume position to offer the user.
    /// loadAndOpen stages the files and resolves position into this,
    /// then waits for the confirmation dialog to either accept the
    /// resume CFI or reset to the start before booting the reader.
    /// Matches the audio player's prompt-to-resume pattern instead
    /// of silently jumping to the last position.
    @State private var pendingResume: ResumeOffer? = nil

    private struct ResumeOffer {
        let htmlURL: URL
        let epubURL: URL
        let cfi: String?
        let fraction: Double
    }

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
        .confirmationDialog(
            "Resume reading?",
            isPresented: Binding(
                get: { pendingResume != nil },
                // The dialog can be dismissed by tapping outside on
                // iPad — treat that as Cancel so the user isn't
                // trapped on a stuck "Where do you want to start?"
                // overlay with no way out.
                set: { if !$0 && pendingResume != nil { cancelResume() } }),
            titleVisibility: .visible,
            presenting: pendingResume
        ) { offer in
            Button("Resume at \(Int(offer.fraction * 100))%") {
                beginBoot(htmlURL: offer.htmlURL, epubURL: offer.epubURL, resumeCfi: offer.cfi)
                pendingResume = nil
            }
            Button("Start from beginning") {
                lastCfi = nil
                progressPct = 0
                beginBoot(htmlURL: offer.htmlURL, epubURL: offer.epubURL, resumeCfi: nil)
                pendingResume = nil
            }
            Button("Cancel", role: .cancel) { cancelResume() }
        }
        .task { await loadAndOpen() }
        .onDisappear { cleanup() }
    }

    /// User chose Cancel (or swiped the dialog away on iPad). Dismiss
    /// the reader entirely — the WebView never mounted, so there's
    /// nothing to tear down beyond clearing the pending offer.
    private func cancelResume() {
        pendingResume = nil
        dismiss()
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

            // Test-mode shortcut: copy the bundled EPUB into staging
            // and skip all gRPC paths. The route only carries
            // `testBundleEpub` when the UI-test launch arg set it,
            // so production navigation flows past this branch
            // unchanged.
            let epubURL: URL
            if let bundleName = route.testBundleEpub {
                epubURL = try copyTestEpubFromBundle(named: bundleName, into: dir)
            } else if let downloadedURL = bookCache.localBookURL(route.mediaItemId) {
                // Offline-friendly fast path: this book is in the
                // explicit-download cache. Stage the local file into
                // the reader dir under the same naming the gRPC path
                // would have produced, so the rest of the boot is
                // identical. No network needed.
                epubURL = try stageLocalEpub(from: downloadedURL, into: dir)
                await resolveResumePosition()
            } else {
                status = .loading("Downloading book…")
                epubURL = try await ensureEpubDownloaded(into: dir)
                await resolveResumePosition()
            }

            // If we have a saved position, hold the WebView mount and
            // ask the user to pick Resume vs Start Over — matches the
            // audio player's prompt-to-resume policy. Test mode and
            // first-time opens (fraction == 0) boot straight through.
            if route.testBundleEpub == nil && progressPct > 0 {
                pendingResume = ResumeOffer(
                    htmlURL: htmlURL,
                    epubURL: epubURL,
                    cfi: lastCfi,
                    fraction: progressPct)
                status = .loading("Where do you want to start?")
            } else {
                beginBoot(htmlURL: htmlURL, epubURL: epubURL, resumeCfi: lastCfi)
            }
        } catch {
            log.error("loadAndOpen failed", error: error)
            status = .error("Couldn't open this book.")
        }
    }

    /// Latches boot args on the bridge and mounts the WebView. The
    /// args must be set BEFORE `stagedReaderHtml` / `stagedEpubFile`
    /// flip non-nil, since that's what triggers the WebView mount —
    /// `didFinish` fires within milliseconds of the local file load
    /// and skips the boot call if args are still nil at that point.
    private func beginBoot(htmlURL: URL, epubURL: URL, resumeCfi: String?) {
        bridge.onMessage = handleBridgeMessage(_:)
        bridge.bootArgs = .init(
            epubFileName: epubURL.lastPathComponent,
            resumeCfi: resumeCfi,
            fontPct: fontPct)
        status = .loading("Opening book…")
        stagedReaderHtml = htmlURL
        stagedEpubFile = epubURL
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

    /// Picks the resume position from the freshest source available.
    /// Local queue wins when newer than the server (covers the
    /// "read offline, queued progress is ahead of what the server
    /// knows" case). Server wins when newer (covers the "read on web
    /// while phone was offline" case). Either side may be nil — if
    /// neither has a position the reader opens at the cover.
    private func resolveResumePosition() async {
        let local = await progressQueue.entry(mediaItemId: route.mediaItemId)
        let remote = await dataModel.readingProgress(mediaItemId: route.mediaItemId)

        // Pick by latest timestamp. The remote side carries
        // `clientRecordedAt` (or falls back to `updatedAt` for
        // pre-V098 rows from old clients) — local always carries
        // its own `recordedAt`.
        let localStamp = local?.recordedAt
        let remoteStamp = remote?.clientRecordedAt ?? remote?.updatedAt
        let useLocal: Bool = {
            switch (localStamp, remoteStamp) {
            case (nil, nil): return false
            case (.some, nil): return true
            case (nil, .some): return false
            case let (lts?, rts?): return lts > rts
            default: return false
            }
        }()

        if useLocal, let local {
            lastCfi = local.locator.isEmpty ? nil : local.locator
            progressPct = local.fraction
        } else if let remote {
            lastCfi = remote.locator.isEmpty ? nil : remote.locator
            progressPct = remote.fraction
        }
    }

    /// Stages a downloaded EPUB from `BookCacheManager`'s permanent
    /// cache into the reader's staging dir under the same name the
    /// gRPC path would have produced. Removes any existing staged
    /// file first so we always run against the latest download —
    /// matters if the user re-downloaded the book after server-side
    /// metadata changes.
    private func stageLocalEpub(from src: URL, into dir: URL) throws -> URL {
        let dest = dir.appendingPathComponent("book-\(route.mediaItemId).epub")
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.copyItem(at: src, to: dest)
        log.info("staged downloaded EPUB into reader dir: \(dest.lastPathComponent)")
        return dest
    }

    /// Test-mode counterpart to `ensureEpubDownloaded`. Resolves the
    /// named resource from `Bundle.main`, copies it into the staging
    /// dir under a sentinel filename so multiple test launches don't
    /// collide with cached production EPUBs, and returns the staged
    /// URL. Throws if the bundled resource is missing — better than
    /// silently rendering nothing.
    private func copyTestEpubFromBundle(named resourceName: String, into dir: URL) throws -> URL {
        let stem = (resourceName as NSString).deletingPathExtension
        let ext = (resourceName as NSString).pathExtension
        guard let src = Bundle.main.url(forResource: stem, withExtension: ext) else {
            throw NSError(domain: "BookReaderView", code: 404, userInfo: [
                NSLocalizedDescriptionKey: "test EPUB '\(resourceName)' not found in app bundle",
            ])
        }
        let dest = dir.appendingPathComponent("test-\(resourceName)")
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.copyItem(at: src, to: dest)
        log.info("test-mode EPUB staged from bundle: \(dest.lastPathComponent)")
        return dest
    }

    private func handleBridgeMessage(_ msg: ReaderBridge.Message) {
        switch msg {
        case .ready:
            status = .reading
            handleReadyApplyPrefs()
        case .relocated(let cfi, let pct):
            lastCfi = cfi
            progressPct = pct
            recordProgressLocally(cfi: cfi, fraction: pct)
        case .error(let m):
            log.warning("reader.html reported error: \(m)")
            status = .error(m)
        }
    }

    /// Writes the current position to the local queue. Replaces the
    /// previous timer-based polling — every relocation now produces
    /// exactly one queue entry, the [ProgressFlusher] handles
    /// shipping it to the server. Test-mode books skip this since
    /// they don't correspond to a server-side row.
    private func recordProgressLocally(cfi: String, fraction: Double) {
        if route.testBundleEpub != nil { return }
        let mediaItemId = route.mediaItemId
        let now = Date()
        Task { @MainActor in
            // Best-effort image-cache + Downloads-view bookkeeping —
            // BookCacheManager only knows downloaded books, no-ops
            // for anything else.
            bookCache.updateLastAccessed(mediaItemId, completedFraction: fraction)
            await progressQueue.record(
                mediaItemId: mediaItemId,
                locator: cfi,
                fraction: fraction,
                recordedAt: now)
        }
    }

    private func increaseFont() {
        fontPct = min(200, fontPct + 10)
        Self.persistFontPct(fontPct)
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    private func decreaseFont() {
        fontPct = max(60, fontPct - 10)
        Self.persistFontPct(fontPct)
        bridge.runJS("window.MMReader.setFont(\(fontPct))")
    }

    // MARK: - Font-size persistence

    /// UserDefaults key for the persisted reader font percentage.
    /// Sibling to `readerTheme` — same pattern. Local-only, never
    /// transmitted to the server.
    private static let fontPctKey = "readerFontPct"

    private static var storedFontPct: Int {
        let raw = UserDefaults.standard.integer(forKey: fontPctKey)
        // `integer(forKey:)` returns 0 for never-set keys; treat that
        // (and any value outside our clamp range) as "no preference"
        // and fall back to the 100 default.
        guard raw >= 60, raw <= 200 else { return 100 }
        return raw
    }

    private static func persistFontPct(_ pct: Int) {
        UserDefaults.standard.set(pct, forKey: fontPctKey)
    }

    private func cleanup() {
        // Best-effort flush so the tail of progress goes to the
        // server before the view tears down. The flusher handles
        // queue iteration + retries; a network failure here just
        // leaves the queue intact for the next opportunity.
        if route.testBundleEpub == nil && lastCfi != nil {
            Task { @MainActor in await progressFlusher.flushNow() }
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
