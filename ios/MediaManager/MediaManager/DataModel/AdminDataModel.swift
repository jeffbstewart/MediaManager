import Foundation

@MainActor protocol AdminDataModel {
    func transcodeStatus() async throws -> TranscodeStatusResponse
    func buddyStatus() async throws -> BuddyStatusResponse
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
    func unmatchedFiles() async throws -> AdminUnmatchedResponse
    func acceptUnmatched(id: UnmatchedFileID) async throws
    func ignoreUnmatched(id: UnmatchedFileID) async throws
    func linkUnmatched(id: UnmatchedFileID, titleId: TitleID) async throws
}
