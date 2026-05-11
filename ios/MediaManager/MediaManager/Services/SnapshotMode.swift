import Foundation

/// UI-test hook for App Store screenshot capture. When the app is
/// launched with `-MMSnapshotMode`, a small number of views suppress
/// any rendering of the connected server's hostname (the demo server
/// URL must never appear in submitted screenshots). Production
/// launches never carry the arg and behave normally.
enum SnapshotMode {
    static let isActive: Bool =
        ProcessInfo.processInfo.arguments.contains("-MMSnapshotMode")
}
