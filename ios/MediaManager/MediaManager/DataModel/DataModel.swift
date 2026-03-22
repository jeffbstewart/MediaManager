import Foundation

@MainActor protocol DataModel: AnyObject, Observable,
    CatalogDataModel, PlaybackDataModel, WishListDataModel,
    ProfileDataModel, LiveDataModel, AdminDataModel {

    var isOnline: Bool { get }
    var capabilities: [String] { get }
    var userInfo: ServerUserInfo? { get }
    var downloads: DownloadManager { get }
    func imageData(path: String) async -> Data?
}

enum DataModelError: LocalizedError {
    case offline
    case notFound
    case serverError(String)

    var errorDescription: String? {
        switch self {
        case .offline: "This feature requires an internet connection"
        case .notFound: "Not found"
        case .serverError(let msg): msg
        }
    }
}
