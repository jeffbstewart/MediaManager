import Foundation
import MediaManagerProtos

// Identifiable conformances for proto types used in SwiftUI lists.
// Live here (alongside the proto module) instead of the app target so
// they don't trip Swift 6's "extension declares a conformance of
// imported type to imported protocol" warning — Identifiable is from
// SwiftUI/Foundation but MediaManagerProtos comes from a sibling
// module, so app-side extensions were "doubly-imported".

extension MMAdminCamera: Identifiable {}

extension MMPendingExpansionItem: Identifiable {
    public var id: Int64 { mediaItemID }
}

extension MMFamilyMemberResponse: Identifiable {}

extension MMTunerResponse: Identifiable {}

extension MMValuationItem: Identifiable {
    public var id: Int64 { mediaItemID }
}
