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

    enum Status: Equatable {
        case loading(String)
        case reading
        case error(String)
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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 16) {
                    if status == .reading {
                        Text("\(Int(progressPct * 100))%")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Button { decreaseFont() } label: { Image(systemName: "textformat.size.smaller") }
                        .disabled(status != .reading || fontPct <= 60)
                    Button { increaseFont() } label: { Image(systemName: "textformat.size.larger") }
                        .disabled(status != .reading || fontPct >= 200)
                }
            }
        }
        .task { await loadAndOpen() }
        .onDisappear { cleanup() }
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
            // Forward all bridge messages through MMLogger so the JS
            // side's step pings + errors land in the iOS log without
            // needing to attach Safari's web inspector.
            log.info("reader bridge: \(body)")
            let parsed: ReaderBridge.Message?
            switch type {
            case "ready":
                parsed = .ready
            case "relocated":
                parsed = .relocated(
                    cfi: (body["cfi"] as? String) ?? "",
                    percent: (body["percent"] as? Double) ?? 0)
            case "error":
                parsed = .error((body["message"] as? String) ?? "Unknown reader error")
            default:
                // step / progress pings — logged above, no UI side-effect
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
