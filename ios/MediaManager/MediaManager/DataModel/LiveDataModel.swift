import Foundation

@MainActor protocol LiveDataModel {
    func cameras() async throws -> ApiCameraListResponse
    func tvChannels() async throws -> ApiTvChannelListResponse
    func warmUpStream(path: String) async throws
}
