import Foundation

struct ApiCamera: Codable, Identifiable {
    let id: Int
    let name: String
    let hlsUrl: String
    let snapshotUrl: String

    enum CodingKeys: String, CodingKey {
        case id, name
        case hlsUrl = "hls_url"
        case snapshotUrl = "snapshot_url"
    }
}

struct ApiCameraListResponse: Codable {
    let cameras: [ApiCamera]
}

struct ApiTvChannel: Codable, Identifiable {
    let id: Int
    let guideNumber: String
    let guideName: String
    let networkAffiliation: String?
    let receptionQuality: Int
    let hlsUrl: String

    enum CodingKeys: String, CodingKey {
        case id
        case guideNumber = "guide_number"
        case guideName = "guide_name"
        case networkAffiliation = "network_affiliation"
        case receptionQuality = "reception_quality"
        case hlsUrl = "hls_url"
    }
}

struct ApiTvChannelListResponse: Codable {
    let channels: [ApiTvChannel]
}
