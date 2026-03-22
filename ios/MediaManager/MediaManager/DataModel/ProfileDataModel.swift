import Foundation

@MainActor protocol ProfileDataModel {
    func profile() async throws -> ProfileResponse
    func updateTvQuality(_ quality: Int) async throws
    func changePassword(current: String, new: String) async throws
    func sessions() async throws -> ApiSessionListResponse
    func deleteSession(id: SessionID, type: SessionType) async throws
    func deleteOtherSessions() async throws
}
