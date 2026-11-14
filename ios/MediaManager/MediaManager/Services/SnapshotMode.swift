import Foundation
import MediaManagerCore

/// App-side name for screenshot mode (`ScreenshotMode` in
/// MediaManagerCore, which owns the switch and explains what the mode
/// does). Views in the app use it to hide the connected server's
/// hostname and to skip network discovery, so the server address never
/// appears in a screenshot.
enum SnapshotMode {
    static var isActive: Bool { ScreenshotMode.isActive }
}
