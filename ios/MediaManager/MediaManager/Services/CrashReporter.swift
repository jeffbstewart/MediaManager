import Foundation
import MetricKit
import Darwin

private let logger = MMLogger(category: "CrashReporter")

// MARK: - Signal-handler backstop
//
// MetricKit delivery is reliable on real devices but flaky in
// simulator and slow in some real-device cases (Apple batches
// payloads). The signal handler captures the same data deterministic-
// ally on every crash, before iOS terminates the process.
//
// Handlers must be **async-signal-safe** — we can use only a small
// list of POSIX functions (`write`, `backtrace`, `backtrace_symbols_fd`,
// `signal`, `kill`, `getpid`, `gettimeofday`, `fsync`, `_exit`).
// No Swift `String`, no `Foundation`, no `print`, no `NSLock`, no
// dynamic allocation. Anything else risks deadlock or undefined
// behaviour because the heap lock / runtime locks may already be
// held by the thread that's about to die.
//
// The state below is pre-allocated at install time and shared
// across all signals. `nonisolated(unsafe)` is the Swift 6 escape
// hatch for "I know the safety story, don't actor-check this."

private nonisolated(unsafe) var sigCrashLogFD: Int32 = -1
private nonisolated(unsafe) let sigBackTraceBuffer =
    UnsafeMutablePointer<UnsafeMutableRawPointer?>.allocate(capacity: 128)
/// Reverse-order ASCII buffer used by [writeAsciiInt]. Pre-allocated
/// at install time; only written to from inside signal handlers.
private nonisolated(unsafe) let sigItoaBuffer =
    UnsafeMutablePointer<CChar>.allocate(capacity: 32)

private let crashSignals: [Int32] = [
    SIGABRT,  // assert / fatalError / Swift runtime aborts
    SIGSEGV,  // EXC_BAD_ACCESS — null deref, memory fault
    SIGBUS,   // unaligned access, mapped-file truncation
    SIGILL,   // bad instruction (often from Swift runtime traps)
    SIGFPE,   // arithmetic fault — div-by-zero, integer overflow
    SIGTRAP,  // EXC_BREAKPOINT — Swift trap on precondition fail
    SIGPIPE,  // broken socket — usually background, but logged for visibility
]

/// Writes an ASCII representation of `value` to `fd`. Async-signal-
/// safe: uses only `write(2)` and a pre-allocated buffer; no
/// allocation, no `snprintf` (POSIX explicitly excludes the printf
/// family from the async-signal-safe list).
private func writeAsciiInt(fd: Int32, _ value: Int) {
    // Build digits backwards into the end of sigItoaBuffer, then
    // emit start..32. Capacity 32 is more than enough for Int64.
    var v = value
    var negative = false
    if v < 0 { negative = true; v = -v }
    var idx = 31
    if v == 0 {
        sigItoaBuffer[idx] = CChar(UInt8(ascii: "0"))
        idx &-= 1
    } else {
        while v > 0 {
            sigItoaBuffer[idx] = CChar(UInt8(ascii: "0") &+ UInt8(v % 10))
            v /= 10
            idx &-= 1
        }
    }
    if negative {
        sigItoaBuffer[idx] = CChar(UInt8(ascii: "-"))
        idx &-= 1
    }
    let start = sigItoaBuffer.advanced(by: idx + 1)
    let len = 31 - idx
    write(fd, start, len)
}

/// Writes a string literal to the FD. `StaticString.utf8Start` is a
/// pointer into the binary's text segment — zero-allocation, safe
/// inside a signal handler.
private func writeStaticString(fd: Int32, _ s: StaticString) {
    s.withUTF8Buffer { buf in
        if let base = buf.baseAddress {
            write(fd, base, buf.count)
        }
    }
}

/// The actual signal handler. Must NOT capture, MUST be
/// `@convention(c)`, MUST only use async-signal-safe primitives.
private let signalHandlerFn: @convention(c) (Int32) -> Void = { signo in
    guard sigCrashLogFD >= 0 else { return }

    // Header: `\n=== iOS signal <N> time <SEC>.<USEC> ===\n`. Each
    // chunk is either a static literal (compiled into the binary's
    // text segment, no allocation) or an int formatted by
    // [writeAsciiInt] into a pre-allocated buffer.
    var ts = timeval()
    gettimeofday(&ts, nil)
    writeStaticString(fd: sigCrashLogFD, "\n=== iOS signal ")
    writeAsciiInt(fd: sigCrashLogFD, Int(signo))
    writeStaticString(fd: sigCrashLogFD, " time ")
    writeAsciiInt(fd: sigCrashLogFD, ts.tv_sec)
    writeStaticString(fd: sigCrashLogFD, ".")
    writeAsciiInt(fd: sigCrashLogFD, Int(ts.tv_usec))
    writeStaticString(fd: sigCrashLogFD, " ===\n")

    // Capture up to 128 frame return addresses — `backtrace(3)` is
    // documented async-signal-safe. `backtrace_symbols_fd(3)` is
    // the explicitly-async-safe variant of `backtrace_symbols(3)`:
    // formats and writes without allocating.
    let count = backtrace(sigBackTraceBuffer, 128)
    backtrace_symbols_fd(sigBackTraceBuffer, count, sigCrashLogFD)
    fsync(sigCrashLogFD)

    // Restore default disposition and re-raise so iOS still gets a
    // clean termination record (and MetricKit can later deliver it
    // through its own channel — additive, not replacement).
    Darwin.signal(signo, SIG_DFL)
    kill(getpid(), signo)
}

/// Routes iOS crash diagnostics from Apple's MetricKit framework into
/// Binnacle via the existing log stream. Apps can't read the raw .ips
/// crash files iOS stores per-process — `MXMetricManager` is the only
/// API that surfaces that data, and it does so on a *subsequent*
/// launch (typically minutes-to-days after the crash, depending on
/// how soon the user re-opens the app).
///
/// Architecture:
///  1. Subscribe to MXMetricManager at app launch.
///  2. When `didReceive(_ payloads:)` fires, extract every
///     `MXCrashDiagnostic` from each payload and serialise it.
///  3. Persist the JSON to `<Application Support>/pending-crashes.json`
///     immediately (in case the LogStreamer isn't yet warm or the app
///     re-crashes before shipping).
///  4. Try to ship anything pending — both newly-arrived diagnostics
///     and any leftovers from a previous launch.
///
/// The persisted-file step matters because MetricKit can deliver
/// crashes during the small window before LogStreamer's gRPC
/// connection is established — losing those diagnostics to a crash-
/// during-startup loop would be bad. Drain on every launch.
@MainActor
final class CrashReporter: NSObject, MXMetricManagerSubscriber {

    private let pendingPath: URL
    /// Where the signal-handler backstop writes its text dump on a
    /// crash. Drained on the next launch and shipped to Binnacle.
    private let signalLogPath: URL

    override init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
        pendingPath = appSupport.appendingPathComponent("pending-crashes.json")
        signalLogPath = appSupport.appendingPathComponent("pending-signal-crash.txt")
        super.init()

        // Drain anything the signal-handler backstop wrote on a
        // previous launch BEFORE we install our own handlers — we
        // want to read the leftover file with normal Foundation
        // I/O before its FD becomes the open one signals write into.
        drainPendingSignalLog()

        // Install signal handlers — backstop for cases where MetricKit
        // doesn't deliver (simulator, fast-relaunch, watchdog kills).
        installSignalHandlers()

        MXMetricManager.shared.add(self)
        // Try once at launch — anything MetricKit handed us in a
        // previous session that didn't ship gets a fresh attempt.
        drainPending()
    }

    private func installSignalHandlers() {
        // Truncate-create the per-launch crash file; FD stays open
        // and writeable for the lifetime of the process. Signals
        // that fire write into it directly. O_APPEND so concurrent
        // writes from multiple signal handlers don't tear (rare —
        // usually one signal kills the process — but cheap insurance).
        let mode: mode_t = 0o600
        let flags: Int32 = O_CREAT | O_WRONLY | O_TRUNC | O_APPEND
        let fd = signalLogPath.path.withCString { open($0, flags, mode) }
        if fd < 0 {
            logger.warning("CrashReporter: couldn't open signal-log fd at \(signalLogPath.path)")
            return
        }
        sigCrashLogFD = fd

        var action = sigaction()
        // sa_handler in the struct is reached via sigaction's __sigaction_u
        // union; on Apple platforms the convenience field is sa_handler.
        action.__sigaction_u = .init(__sa_handler: signalHandlerFn)
        sigemptyset(&action.sa_mask)
        // SA_RESETHAND would auto-restore default disposition after
        // the first signal — we do it manually inside the handler so
        // the handler also gets to log first. SA_NODEFER lets nested
        // signals re-enter (rare but possible). SA_ONSTACK would let
        // us run on a separate signal stack; we skip it because it
        // requires sigaltstack setup.
        action.sa_flags = 0

        for sig in crashSignals {
            sigaction(sig, &action, nil)
        }
        logger.info("CrashReporter: signal-handler backstop armed at \(signalLogPath.path)")
    }

    /// Reads the signal-handler text file from a previous launch (if
    /// any), ships it to Binnacle as a single ERROR log entry, and
    /// removes the file. Runs BEFORE the new handlers are installed
    /// so we don't open a write-FD on the same path.
    private func drainPendingSignalLog() {
        guard FileManager.default.fileExists(atPath: signalLogPath.path) else { return }
        let body = (try? String(contentsOf: signalLogPath, encoding: .utf8)) ?? "<unreadable>"
        // First line of the file is our `=== iOS signal N time S.U ===`
        // header, which makes a useful summary. Truncate to keep
        // log-search readable; full text rides along as an attribute.
        let firstLine = body.split(
            separator: "\n", maxSplits: 1, omittingEmptySubsequences: true
        ).first.map(String.init) ?? "iOS signal crash"
        logger.error(
            "iOS crash (signal): \(firstLine)",
            attributes: ["crashKind": "signal", "signalLog": body])
        try? FileManager.default.removeItem(at: signalLogPath)
    }

    // MARK: - MXMetricManagerSubscriber

    /// Apple's preferred crash-diagnostic delivery mechanism on iOS 14+.
    /// MetricKit calls this on a background queue with payloads from
    /// previous sessions. We extract the structured data inline (off-
    /// MainActor) and hand the resulting Codable records to MainActor
    /// for persistence — `MXDiagnosticPayload` isn't Sendable, so we
    /// can't let the raw Apple type cross actor boundaries.
    nonisolated func didReceive(_ payloads: [MXDiagnosticPayload]) {
        var newRecords: [PendingCrash] = []
        for payload in payloads {
            for crash in (payload.crashDiagnostics ?? []) {
                newRecords.append(PendingCrash(from: crash))
            }
            // Also capture hangs — too-long main-thread blocks show
            // up here. CPU / disk-write exceptions are too noisy to
            // ship as crashes; skipping them.
            for hang in (payload.hangDiagnostics ?? []) {
                newRecords.append(PendingCrash(from: hang))
            }
        }
        if newRecords.isEmpty { return }
        Task { @MainActor [weak self] in
            self?.handleNewRecords(newRecords)
        }
    }

    /// Required by the protocol but not the channel iOS uses for
    /// crashes — MXMetricPayload is hourly performance data we don't
    /// care about. Implement to satisfy the protocol; ignore the
    /// payloads.
    nonisolated func didReceive(_ payloads: [MXMetricPayload]) {
        // intentionally empty — we only care about diagnostics.
    }

    // MARK: - Internal

    private func handleNewRecords(_ newRecords: [PendingCrash]) {
        let merged = readPending() + newRecords
        writePending(merged)
        logger.warning("CrashReporter: queued \(newRecords.count) crash diagnostic(s) for shipping")
        drainPending()
    }

    /// Reads any persisted records, ships each as a structured log
    /// entry, and removes the file on success. Called at startup and
    /// after every fresh `didReceive`. Failure to ship leaves the
    /// file in place — next launch retries.
    private func drainPending() {
        let pending = readPending()
        guard !pending.isEmpty else { return }
        logger.warning("CrashReporter: draining \(pending.count) pending crash record(s)")
        for record in pending {
            // Severity = .error (the highest the iOS-side enum exposes
            // — there's no .fatal — but enough to stand out in
            // Binnacle severity filters). Attributes carry the
            // structured fields; the message is a short summary.
            logger.error(
                "iOS crash: \(record.summary)",
                attributes: record.attributes)
        }
        // The LogBuffer's drainer is async — the records are
        // queued, not necessarily delivered. We still clear the
        // pending file on the assumption that the buffer has at
        // least claimed them. Anything that fails to ship from the
        // buffer is logged separately by the LogStreamer; we don't
        // double-buffer.
        try? FileManager.default.removeItem(at: pendingPath)
    }

    private func readPending() -> [PendingCrash] {
        guard FileManager.default.fileExists(atPath: pendingPath.path) else { return [] }
        do {
            let data = try Data(contentsOf: pendingPath)
            return try JSONDecoder().decode([PendingCrash].self, from: data)
        } catch {
            logger.warning("CrashReporter: failed to read pending crashes (\(error.localizedDescription)); discarding")
            try? FileManager.default.removeItem(at: pendingPath)
            return []
        }
    }

    private func writePending(_ records: [PendingCrash]) {
        do {
            let data = try JSONEncoder().encode(records)
            try data.write(to: pendingPath, options: [.atomic])
        } catch {
            logger.warning("CrashReporter: failed to persist crash records: \(error.localizedDescription)")
        }
    }
}

/// On-disk representation of a single MetricKit crash / hang record.
/// Codable so a partial drain leaves remaining records on disk in a
/// shape the next launch can re-read.
private struct PendingCrash: Codable {
    let kind: Kind
    let receivedAt: Date
    let appVersion: String?
    /// Native signal number (SIGABRT=6, SIGSEGV=11, etc.).
    let signal: Int?
    /// Mach exception type (EXC_BREAKPOINT=6, EXC_BAD_ACCESS=1, etc.).
    let exceptionType: Int?
    let exceptionCode: Int?
    /// Free-form OS-supplied reason (e.g. "Namespace SIGNAL, Code 0x6").
    let terminationReason: String?
    /// Big — the full call-stack tree as JSON. Worth shipping every
    /// time; usually 5-30 KB. Binnacle has plenty of room.
    let callStackTreeJson: String?
    /// On hang diagnostics, a duration measure of how long the main
    /// thread was unresponsive.
    let hangDurationSeconds: Double?

    enum Kind: String, Codable { case crash, hang }

    init(from crash: MXCrashDiagnostic) {
        kind = .crash
        receivedAt = Date()
        appVersion = crash.applicationVersion
        signal = crash.signal?.intValue
        exceptionType = crash.exceptionType?.intValue
        exceptionCode = crash.exceptionCode?.intValue
        terminationReason = crash.terminationReason
        callStackTreeJson = String(
            data: crash.callStackTree.jsonRepresentation(),
            encoding: .utf8)
        hangDurationSeconds = nil
    }

    init(from hang: MXHangDiagnostic) {
        kind = .hang
        receivedAt = Date()
        appVersion = hang.applicationVersion
        signal = nil
        exceptionType = nil
        exceptionCode = nil
        terminationReason = nil
        callStackTreeJson = String(
            data: hang.callStackTree.jsonRepresentation(),
            encoding: .utf8)
        hangDurationSeconds = hang.hangDuration.converted(to: .seconds).value
    }

    /// One-line human summary used as the Binnacle log message body.
    /// Keeps the readable bits inline so log lists scan; the full
    /// payload rides along in `attributes`.
    var summary: String {
        switch kind {
        case .crash:
            let sigName = signalName(signal)
            let exc = exceptionType.map { "exc=0x\(String($0, radix: 16))" } ?? ""
            let v = appVersion.map { "v\($0) " } ?? ""
            return "\(v)\(sigName) \(exc) \(terminationReason ?? "")".trimmingCharacters(in: .whitespaces)
        case .hang:
            let dur = hangDurationSeconds.map { String(format: "%.1fs", $0) } ?? "?"
            let v = appVersion.map { "v\($0) " } ?? ""
            return "\(v)hang \(dur) on main thread"
        }
    }

    var attributes: [String: String] {
        var a: [String: String] = ["crashKind": kind.rawValue]
        if let v = appVersion { a["appVersion"] = v }
        if let s = signal { a["signal"] = String(s) }
        if let s = signal, let n = signalNameRaw(s) { a["signalName"] = n }
        if let e = exceptionType { a["exceptionType"] = String(e) }
        if let c = exceptionCode { a["exceptionCode"] = String(c) }
        if let r = terminationReason { a["terminationReason"] = r }
        if let d = hangDurationSeconds { a["hangDurationSeconds"] = String(d) }
        if let stack = callStackTreeJson { a["callStackTree"] = stack }
        return a
    }

    private func signalName(_ s: Int?) -> String {
        guard let s, let n = signalNameRaw(s) else { return "crash" }
        return n
    }

    private func signalNameRaw(_ s: Int) -> String? {
        switch s {
        case 1: return "SIGHUP"
        case 2: return "SIGINT"
        case 4: return "SIGILL"
        case 5: return "SIGTRAP"
        case 6: return "SIGABRT"
        case 7: return "SIGEMT"
        case 8: return "SIGFPE"
        case 9: return "SIGKILL"
        case 10: return "SIGBUS"
        case 11: return "SIGSEGV"
        case 13: return "SIGPIPE"
        case 14: return "SIGALRM"
        case 15: return "SIGTERM"
        default: return nil
        }
    }
}
