import SwiftUI
import VisionKit

/// Camera barcode scanner using VisionKit's DataScannerViewController.
/// Scans UPC-A (via EAN-13), UPC-E, and EAN-8 barcodes from the device camera.
struct BarcodeScannerView: UIViewControllerRepresentable {
    let onBarcodeScanned: (String) -> Void
    let onDismiss: () -> Void

    static var isAvailable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [
                .barcode(symbologies: [.ean13, .upce, .ean8])
            ],
            qualityLevel: .accurate,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        if !scanner.isScanning {
            try? scanner.startScanning()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onBarcodeScanned: onBarcodeScanned, onDismiss: onDismiss)
    }

    class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onBarcodeScanned: (String) -> Void
        let onDismiss: () -> Void
        private var hasScanned = false

        init(onBarcodeScanned: @escaping (String) -> Void, onDismiss: @escaping () -> Void) {
            self.onBarcodeScanned = onBarcodeScanned
            self.onDismiss = onDismiss
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !hasScanned else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item {
                    if let value = barcode.payloadStringValue {
                        hasScanned = true
                        dataScanner.stopScanning()
                        onBarcodeScanned(value)
                        onDismiss()
                        return
                    }
                }
            }
        }
    }
}
