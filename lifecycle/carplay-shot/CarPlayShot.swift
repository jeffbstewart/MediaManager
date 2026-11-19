// CarPlayShot: screenshots of one window that the operator picks.
//
// Lets an agent take screenshots of Apple's CarPlay Simulator window
// without Screen Recording permission. On launch, macOS shows its window
// picker (SCContentSharingPicker); the operator chooses a window, and
// macOS lets this process stream that window and nothing else, for as
// long as it runs. Nothing is stored: every launch needs a fresh pick.
//
// It must be a stream: macOS refuses a one-off screenshot
// (SCScreenshotManager) of a picked window without Screen Recording
// permission, but allows an SCStream of it. The stream runs at two
// frames a second, and a request saves the latest frame.
//
// Verified 2026-11-19: with Screen Recording permission revoked and every
// permission prompt refused, picking the window was enough. Do not grant
// this helper Screen Recording; it does not need it. (An earlier build
// that used SCScreenshotManager did prompt for it.)
//
// Driven by lifecycle/carplay-shot.sh:
//   CarPlayShot <work dir>
//     writes <work dir>/carplay-shot.pid and <work dir>/carplay-shot.log;
//     on SIGUSR1 reads the file name from <work dir>/request, saves the
//     latest frame to <work dir>/<name>.png, and logs "saved <path>".
//     SIGTERM, cancelling the picker, or the stream stopping quits.

import AppKit
import CoreImage
import ScreenCaptureKit
import UniformTypeIdentifiers

final class Shooter: NSObject, SCContentSharingPickerObserver, SCStreamOutput, SCStreamDelegate {
    private let workDir: URL
    private let logURL: URL
    private var stream: SCStream?
    private let frameQueue = DispatchQueue(label: "carplayshot.frames")
    private let ciContext = CIContext()
    private let lock = NSLock()
    /// Guarded by `lock`.
    private var latest: CGImage?

    init(workDir: URL) {
        self.workDir = workDir
        self.logURL = workDir.appendingPathComponent("carplay-shot.log")
        super.init()
    }

    func log(_ message: String) {
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
        if let handle = try? FileHandle(forWritingTo: logURL) {
            handle.seekToEndOfFile()
            handle.write(Data(line.utf8))
            try? handle.close()
        } else {
            try? Data(line.utf8).write(to: logURL)
        }
    }

    func start() {
        let picker = SCContentSharingPicker.shared
        var config = SCContentSharingPickerConfiguration()
        config.allowedPickerModes = [.singleWindow]
        picker.defaultConfiguration = config
        picker.add(self)
        picker.isActive = true
        picker.present()
        log("picker shown; waiting for a window to be chosen")
    }

    private func quit() {
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }

    // MARK: SCContentSharingPickerObserver

    func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        log("picker cancelled; quitting")
        quit()
    }

    func contentSharingPicker(_ picker: SCContentSharingPicker,
                              didUpdateWith filter: SCContentFilter,
                              for stream: SCStream?) {
        Task { await startStream(filter) }
    }

    func contentSharingPickerStartDidFailWithError(_ error: Error) {
        log("picker failed: \(error.localizedDescription); quitting")
        quit()
    }

    // MARK: Stream

    private func startStream(_ filter: SCContentFilter) async {
        let config = SCStreamConfiguration()
        let scale = CGFloat(filter.pointPixelScale)
        config.width = Int(filter.contentRect.width * scale)
        config.height = Int(filter.contentRect.height * scale)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 2)
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = false
        do {
            if let old = stream {
                try? await old.stopCapture()
            }
            let newStream = SCStream(filter: filter, configuration: config, delegate: self)
            try newStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: frameQueue)
            try await newStream.startCapture()
            stream = newStream
            log("window chosen; streaming; ready")
        } catch {
            log("error: could not start the stream: \(error.localizedDescription); quitting")
            quit()
        }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let image = CIImage(cvPixelBuffer: pixels)
        guard let cg = ciContext.createCGImage(image, from: image.extent) else { return }
        lock.withLock { latest = cg }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("stream stopped: \(error.localizedDescription); quitting")
        quit()
    }

    // MARK: Capture

    /// Saves the latest frame of the chosen window to `<workDir>/<name>.png`.
    func capture() {
        let requestURL = workDir.appendingPathComponent("request")
        let raw = (try? String(contentsOf: requestURL, encoding: .utf8)) ?? ""
        // A bare file name only: no paths, so a request can't write
        // anywhere but the work directory.
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: CharacterSet(charactersIn: "/\\")).last ?? ""
        guard !name.isEmpty, name != ".", name != ".." else {
            log("error: no usable name in request")
            return
        }
        guard let image = lock.withLock({ latest }) else {
            log("error: no frame yet (no window chosen, or nothing drawn)")
            return
        }
        let out = workDir.appendingPathComponent("\(name).png")
        guard let dest = CGImageDestinationCreateWithURL(
                out as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            log("error: cannot write \(out.path)")
            return
        }
        CGImageDestinationAddImage(dest, image, nil)
        if CGImageDestinationFinalize(dest) {
            log("saved \(out.path)")
        } else {
            log("error: could not finish writing \(out.path)")
        }
    }
}

// MARK: - Main

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write(Data("usage: CarPlayShot <work dir>\n".utf8))
    exit(2)
}
let workDir = URL(fileURLWithPath: args[1], isDirectory: true)
try? FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
try? Data("\(getpid())\n".utf8).write(to: workDir.appendingPathComponent("carplay-shot.pid"))

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let shooter = Shooter(workDir: workDir)

signal(SIGUSR1, SIG_IGN)
let shotSource = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
shotSource.setEventHandler { shooter.capture() }
shotSource.resume()

signal(SIGTERM, SIG_IGN)
let quitSource = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
quitSource.setEventHandler {
    shooter.log("stopping")
    try? FileManager.default.removeItem(at: workDir.appendingPathComponent("carplay-shot.pid"))
    exit(0)
}
quitSource.resume()

/// Bumped whenever the source changes, so the log shows which build ran.
let buildNumber = 2
shooter.log("starting build \(buildNumber), pid \(getpid())")
DispatchQueue.main.async { shooter.start() }
app.run()
