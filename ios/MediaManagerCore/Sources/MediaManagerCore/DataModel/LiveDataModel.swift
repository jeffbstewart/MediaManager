import Foundation
import MediaManagerProtos

@MainActor public protocol LiveDataModel {
    func cameras() async throws -> ApiCameraListResponse
    func tvChannels() async throws -> ApiTvChannelListResponse
    func warmUpStream(path: String) async throws
}
