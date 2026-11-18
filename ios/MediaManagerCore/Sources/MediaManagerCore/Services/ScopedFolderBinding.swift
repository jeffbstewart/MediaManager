import Foundation

/// Where a store kept in a `StorageScope` area reads and writes, as of
/// its last look at the scope. Shared by the progress stores.
enum ScopedFolderBinding: Sendable {
    /// `StorageScope.current` has not been read yet (launch).
    case notYetRead
    /// No scope bound: serve nothing, write nothing.
    case unbound
    /// The bound scope's folder for the store's area.
    case bound(URL)

    var folder: URL? {
        if case .bound(let url) = self { return url }
        return nil
    }
}
