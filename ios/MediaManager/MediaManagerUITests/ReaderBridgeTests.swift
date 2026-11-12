import XCTest
import WebKit

/// Reader-bridge tests for the iOS book reader.
///
/// What this file covers:
///
///   1. Fixtures are bundled (reader.html, epub.min.js, jszip.min.js,
///      test.epub).
///   2. reader.html loads in a WKWebView and the JS-side bridge
///      (`window.MMReader.{boot, setFont, setTheme, next, prev,
///      getToc, gotoHref}`) is defined after didFinish.
///   3. file:// XHR can fetch test.epub from the loaded page —
///      proves the WebView config flags (`allowFileAccessFromFileURLs`,
///      `allowUniversalAccessFromFileURLs`) are correctly set.
///   4. epub.js opens the test EPUB, parses it, and exposes a 3-entry
///      spine — proves the library runs in this WKWebView and the
///      fixture has the structure layer-5 tests assume.
///
/// What this file does NOT cover, and why:
///
/// epub.js's `rendition.display()` (the path that loads a chapter
/// into an iframe and fires `ready`) depends on a fully alive
/// WebKit rendering loop: `requestAnimationFrame`, `ResizeObserver`,
/// document-paint scheduling, and queue ticks chained on those.
/// The XCTest runner's WKWebView is off-screen; WebKit progressively
/// suspends those subsystems for off-screen views, and the queue
/// inside epub.js stalls waiting for `manager.rendered` to settle.
/// Polyfilling each piece would amount to reimplementing WebKit's
/// display loop in JS to test code that runs fine when on-screen.
///
/// Full boot-to-ready coverage (theme switching, font scaling, TOC
/// navigation against a rendered iframe) is intentionally deferred
/// to UI testing — XCUIApplication launching the real app, opening
/// a book, and snapshot-asserting on the rendered reader.
@MainActor
final class ReaderBridgeTests: XCTestCase {

    // MARK: - Layer 1: fixture bundling

    private static let expectedFixtures: [(name: String, minBytes: Int)] = [
        ("reader.html", 1000),
        ("epub.min.js", 50_000),
        ("jszip.min.js", 50_000),
        ("test.epub", 1000),
    ]

    private func locateFixture(_ name: String) -> URL? {
        let bundle = Bundle(for: type(of: self))
        let split = name.split(separator: ".", maxSplits: 1).map(String.init)
        let stem = split.first ?? name
        let ext = split.dropFirst().first
        if let url = bundle.url(forResource: stem, withExtension: ext) {
            return url
        }
        if let url = bundle.url(forResource: stem, withExtension: ext, subdirectory: "Fixtures") {
            return url
        }
        return nil
    }

    func testFixturesAreBundled() {
        let bundle = Bundle(for: type(of: self))
        print("[ReaderBridgeTests] test bundle: \(bundle.bundleURL.path)")

        for (name, minBytes) in Self.expectedFixtures {
            guard let url = locateFixture(name) else {
                XCTFail("fixture '\(name)' not found in test bundle (\(bundle.bundleURL.path))")
                continue
            }
            print("[ReaderBridgeTests] found \(name) at \(url.path)")

            let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
            let size = (attrs?[.size] as? NSNumber)?.intValue ?? 0
            XCTAssertGreaterThan(
                size, minBytes,
                "fixture '\(name)' is too small (\(size) bytes < \(minBytes)) — likely a stub or empty copy")
        }
    }

    // MARK: - Layer 2: WebView load + bridge presence

    /// Loads reader.html directly from the test bundle, waits for
    /// `didFinish`, and asserts the JS-side bridge surface is intact.
    /// Catches: bridge function renames, JS syntax errors that break
    /// the IIFE, jszip / epub.js load failures.
    func testReaderHtmlLoadsAndBridgeDefined() {
        let webView = loadReaderHtml()

        let mmType = evalSync(webView, "typeof window.MMReader")
        XCTAssertEqual(mmType, "object",
            "window.MMReader should be defined; got '\(mmType)'")

        for fn in ["boot", "setFont", "setTheme", "next", "prev", "getToc", "gotoHref"] {
            let t = evalSync(webView, "typeof window.MMReader.\(fn)")
            XCTAssertEqual(t, "function",
                "window.MMReader.\(fn) should be a function; got '\(t)'")
        }

        let epubLib = evalSync(webView, "typeof window.ePub")
        XCTAssertEqual(epubLib, "function", "window.ePub (epub.js) should be loaded; got '\(epubLib)'")
        let jszipLib = evalSync(webView, "typeof window.JSZip")
        XCTAssertEqual(jszipLib, "function", "window.JSZip should be loaded; got '\(jszipLib)'")
    }

    // MARK: - Layer 3: file:// XHR

    /// Fetches `test.epub` via XMLHttpRequest from the loaded page.
    /// Catches: a regression in the WebView's file:// access flags
    /// — without this allowance epub.js's internal fetcher would
    /// silently 404 and the reader would never open a book.
    func testEpubFetchableViaXHR() {
        let webView = loadReaderHtml()

        let body = """
        const res = await new Promise((resolve) => {
          const xhr = new XMLHttpRequest();
          xhr.open('GET', 'test.epub', true);
          xhr.responseType = 'arraybuffer';
          xhr.onload = () => resolve({
            ok: true,
            status: xhr.status,
            bytes: xhr.response ? xhr.response.byteLength : 0,
          });
          xhr.onerror = () => resolve({
            ok: false,
            status: xhr.status,
            error: 'xhr.onerror fired',
          });
          xhr.send();
        });
        return JSON.stringify(res);
        """
        let json = callAsyncSync(webView, body)
        print("[ReaderBridgeTests] xhr test.epub → \(json)")
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("could not parse XHR result JSON: \(json)")
            return
        }
        XCTAssertEqual(parsed["ok"] as? Bool, true,
            "XHR for test.epub failed: \(parsed)")
        let bytes = (parsed["bytes"] as? Int) ?? 0
        XCTAssertGreaterThan(bytes, 1000,
            "test.epub fetched but only \(bytes) bytes — file:// XHR may be returning a stub")
    }

    // MARK: - Layer 4: epub.js parses the EPUB

    /// Opens the test EPUB through epub.js (no rendering — that path
    /// hangs in off-screen WebViews) and asserts the spine has the
    /// three sections we ship in the fixture, with non-empty hrefs.
    /// Catches: epub.js library upgrade breakage, fixture format
    /// drift, missing manifest fields.
    func testEpubJsParsesBookSpine() {
        let webView = loadReaderHtml()

        let body = """
        try {
          const book = window.ePub('test.epub', { openAs: 'epub' });
          await Promise.race([
            book.opened,
            new Promise((_, rej) => setTimeout(() => rej(new Error('book.opened timeout 5s')), 5000)),
          ]);
          const sections = (book.spine && book.spine.spineItems) ? book.spine.spineItems : [];
          const summary = sections.map(s => ({ idref: s.idref, href: s.href, index: s.index }));
          return JSON.stringify({ ok: true, count: sections.length, sections: summary });
        } catch (e) {
          return JSON.stringify({ ok: false, error: String(e && e.message || e) });
        }
        """
        let json = callAsyncSync(webView, body)
        print("[ReaderBridgeTests] book spine → \(json)")
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("could not parse spine result JSON: \(json)")
            return
        }
        XCTAssertEqual(parsed["ok"] as? Bool, true,
            "epub.js failed to open book; error=\(parsed["error"] ?? "?")")
        XCTAssertEqual((parsed["count"] as? Int) ?? -1, 3,
            "fixture EPUB should have 3 spine sections; got \(parsed["count"] ?? "?")")
        let sections = (parsed["sections"] as? [[String: Any]]) ?? []
        for (i, s) in sections.enumerated() {
            let href = (s["href"] as? String) ?? ""
            XCTAssertFalse(href.isEmpty, "section \(i) should have a non-empty href")
        }
    }

    // MARK: - WebView setup helper

    /// Creates a fresh WKWebView, loads `reader.html` from the test
    /// bundle, waits up to 15 s for `didFinish`, and returns it. The
    /// associated `Harness` (navigation + script-message delegate) is
    /// reachable via `objc_getAssociatedObject(_, &harnessAssocKey)`,
    /// though all surviving tests treat it as fire-and-forget after
    /// the load completes.
    private func loadReaderHtml() -> WKWebView {
        guard let htmlURL = locateFixture("reader.html") else {
            XCTFail("reader.html missing from test bundle")
            return WKWebView()
        }
        let bundle = Bundle(for: type(of: self))
        print("[ReaderBridgeTests] loading \(htmlURL.path)")
        print("[ReaderBridgeTests] read-access root: \(bundle.bundleURL.path)")

        let config = WKWebViewConfiguration()
        // file:// pages can't normally XHR sibling files; flipping
        // these private prefs allows it. Same flags the production
        // BookReaderView uses.
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        config.setValue(true, forKey: "allowUniversalAccessFromFileURLs")

        let harness = Harness()
        // `reader` is the message-handler name reader.html uses:
        //   window.webkit.messageHandlers.reader.postMessage(...)
        config.userContentController.add(harness, name: "reader")

        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 800, height: 1200), configuration: config)
        webView.navigationDelegate = harness
        // Keep the harness alive until the test method returns —
        // WKWebView's navigationDelegate is `weak`, so without an
        // owning reference it'd dealloc immediately and didFinish
        // would never fire.
        objc_setAssociatedObject(webView, &Self.harnessAssocKey, harness, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

        let didFinish = expectation(description: "WKWebView didFinish navigation for reader.html")
        harness.onFinish = {
            print("[ReaderBridgeTests] didFinish")
            didFinish.fulfill()
        }
        harness.onFail = { error in
            XCTFail("navigation failed: \(error)")
            didFinish.fulfill()
        }

        webView.loadFileURL(htmlURL, allowingReadAccessTo: bundle.bundleURL)
        wait(for: [didFinish], timeout: 15)
        harness.onFinish = nil
        harness.onFail = nil
        return webView
    }

    private static var harnessAssocKey: UInt8 = 0

    // MARK: - JS eval helpers

    /// Synchronously evaluate a JS expression and return its result
    /// stringified. Used for typeof / property reads — anything that
    /// returns a Promise should go through `callAsyncSync` instead.
    private func evalSync(_ webView: WKWebView, _ script: String) -> String {
        let exp = expectation(description: "eval: \(script)")
        let box = StringBox()
        webView.evaluateJavaScript(script) { result, error in
            if let error {
                box.value = "ERROR: \(error.localizedDescription)"
            } else if let s = result as? String {
                box.value = s
            } else if let n = result as? NSNumber {
                box.value = n.stringValue
            } else if let b = result as? Bool {
                box.value = b ? "true" : "false"
            } else if result == nil {
                box.value = "<nil>"
            } else {
                box.value = String(describing: result!)
            }
            exp.fulfill()
        }
        wait(for: [exp], timeout: 5)
        print("[ReaderBridgeTests] eval \(script) → \(box.value)")
        return box.value
    }

    /// Runs an async-style JS body via `callAsyncJavaScript` (which,
    /// unlike `evaluateJavaScript`, awaits Promises and surfaces the
    /// resolved value). 10-second outer timeout — bodies in this
    /// file are file:// XHR or epub.js parsing, both well under
    /// that ceiling on a healthy run.
    private func callAsyncSync(_ webView: WKWebView, _ body: String) -> String {
        let exp = expectation(description: "callAsyncJavaScript")
        let box = StringBox()
        webView.callAsyncJavaScript(body, arguments: [:], in: nil, in: .page) { result in
            switch result {
            case .success(let value):
                if let s = value as? String { box.value = s }
                else if let n = value as? NSNumber { box.value = n.stringValue }
                else if value is NSNull { box.value = "<nil>" }
                else { box.value = String(describing: value) }
            case .failure(let error):
                box.value = "ERROR: \(error.localizedDescription)"
                XCTFail("callAsyncJavaScript failed: \(error.localizedDescription)")
            }
            exp.fulfill()
        }
        wait(for: [exp], timeout: 10)
        return box.value
    }
}

/// Reference-typed `String` slot so we can mutate from a Sendable
/// completion handler without tripping strict-concurrency checks.
/// Marked `@unchecked Sendable` because the synchronisation is
/// supplied by the surrounding XCTestExpectation wait, not by the
/// box itself.
private final class StringBox: @unchecked Sendable {
    var value: String = ""
}

/// Captures WKNavigationDelegate's didFinish / didFail events and
/// the JS-side bridge messages for layer-2/3/4 tests.
private final class Harness: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    var onFinish: (() -> Void)?
    var onFail: ((Error) -> Void)?

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        onFinish?()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        onFail?(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        onFail?(error)
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        // Surviving tests don't depend on any bridge messages, but
        // we still log so unexpected ones (errors from inside
        // reader.html's IIFE, for instance) show up in the test
        // output rather than being silently swallowed.
        print("[Harness] message: \(message.body)")
    }
}
