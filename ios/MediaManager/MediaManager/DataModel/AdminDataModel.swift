import Foundation

@MainActor protocol AdminDataModel {
    func transcodeStatus() async throws -> TranscodeStatusResponse
    func buddyStatus() async throws -> BuddyStatusResponse
    func monitorTranscodeStatus(onUpdate: @Sendable @escaping (MMTranscodeStatusUpdate) async -> Void) async throws
    func scanNas() async throws
    func clearFailures() async throws
    func adminSettings() async throws -> AdminSettingsResponse
    func updateSetting(key: String, value: String?) async throws
    func linkedTranscodes(page: Int) async throws -> AdminLinkedTranscodeResponse
    func unlinkTranscode(id: TranscodeID) async throws
    func adminTags() async throws -> ApiTagListResponse
    func createTag(name: String, color: String) async throws
    func updateTag(id: TagID, name: String, color: String) async throws
    func deleteTag(id: TagID) async throws
    func dataQuality(page: Int) async throws -> AdminDataQualityResponse
    func reEnrich(titleId: TitleID) async throws
    func deleteTitle(id: TitleID) async throws
    func purchaseWishes() async throws -> AdminPurchaseWishListResponse
    func updatePurchaseWishStatus(tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int?, status: AcquisitionStatus) async throws
    func adminUsers() async throws -> AdminUserListResponse
    func createUser(username: String, password: String, displayName: String?, accessLevel: Int) async throws
    func updateUserRole(id: UserID, accessLevel: Int) async throws
    func updateUserRatingCeiling(id: UserID, ceiling: Int?) async throws
    func unlockUser(id: UserID) async throws
    func forcePasswordChange(id: UserID) async throws
    func resetPassword(id: UserID, newPassword: String) async throws
    func deleteUser(id: UserID) async throws
    func submitBarcode(upc: String) async throws -> MMSubmitBarcodeResponse
    func monitorScanProgress(onUpdate: @Sendable @escaping (MMScanProgressUpdate) async -> Void) async throws
    func getScanDetail(scanId: Int64) async throws -> MMScanDetailResponse
    func assignTmdb(titleId: Int64, tmdbId: Int32, mediaType: MMMediaType) async throws -> MMAssignTmdbResponse
    func updatePurchaseInfo(scanId: Int64, place: String?, date: MMCalendarDate?, price: Double?) async throws
    func uploadOwnershipPhoto(scanId: Int64, photoData: Data, contentType: String) async throws -> MMUploadOwnershipPhotoResponse
    func deleteOwnershipPhoto(photoId: String) async throws
    func adminListCameras() async throws -> MMAdminCameraListResponse
    func adminCreateCamera(name: String, rtspUrl: String, snapshotUrl: String, streamName: String?, enabled: Bool) async throws -> MMAdminCamera
    func adminUpdateCamera(id: Int64, name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Bool) async throws -> MMAdminCamera
    func adminDeleteCamera(id: Int64) async throws
    func adminReorderCameras(ids: [Int64]) async throws
    func unmatchedFiles() async throws -> AdminUnmatchedResponse
    func acceptUnmatched(id: UnmatchedFileID) async throws
    func ignoreUnmatched(id: UnmatchedFileID) async throws
    func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws
}
