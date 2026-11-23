import Foundation
import MediaManagerCore

/// The words CarPlay shows for each reason it has no music to offer.
/// The text is in Localizable.xcstrings; the `.carPlay…` names are
/// symbols Xcode generates from its keys, so a missing key fails the
/// build. The translator notes there carry the rule that matters most:
/// say the fix waits until the driver is not driving.
extension CarPlayUnavailableReason {
    var title: LocalizedStringResource {
        switch self {
        case .notSignedIn: .carPlayNotSignedInTitle
        case .termsNotAccepted: .carPlayTermsNotAcceptedTitle
        case .serverChanged: .carPlayServerChangedTitle
        case .offlineMode: .carPlayOfflineModeTitle
        }
    }

    var detail: LocalizedStringResource {
        switch self {
        case .notSignedIn: .carPlayNotSignedInDetail
        case .termsNotAccepted: .carPlayTermsNotAcceptedDetail
        case .serverChanged: .carPlayServerChangedDetail
        case .offlineMode: .carPlayOfflineModeDetail
        }
    }
}
