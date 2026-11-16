import SwiftUI
import MediaManagerCore

/// First screen on a device that hasn't been asked yet: explains the
/// adults-only rule, then Next hands over to Apple's age-range sheet.
struct AgeGateIntroView: View {
    @Environment(AgeGate.self) private var ageGate
    @State private var asking = false

    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 16

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 12)

                MMBrandWordmark(size: 34)
                    .padding(.top)
                    .padding(.horizontal, 16)

                Text("For Adults Only")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .multilineTextAlignment(.center)

                Text("Household Disc Keeper is only available to adults. When you tap Next, you'll be asked to share your age range with the app.")
                    .font(.system(size: bodySize))
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                Text("The app learns only whether you're 18 or older, never your birthday, and asks just once on this device.")
                    .font(.system(size: bodySize))
                    .foregroundStyle(MarqueeTheme.Colors.text3)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                Button(action: ask) {
                    if asking {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text("Next").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.mmPrimary)
                .disabled(asking)
                .padding(.horizontal, 24)
                .accessibilityIdentifier("age-gate-next")

                Spacer(minLength: 12)
            }
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .mmGlowPage()
    }

    private func ask() {
        asking = true
        Task {
            let answer: AgeGateAnswer
            #if DEBUG
            if let simulated = AgeGate.simulatedAnswer {
                answer = simulated
            } else {
                answer = await requestFromApple()
            }
            #else
            answer = await requestFromApple()
            #endif
            ageGate.record(answer)
            if ageGate.refusesService {
                // Anyone signed in on this device (an existing install
                // updated to this build) is signed out, and anything
                // playing stops, CarPlay included.
                AppServices.shared.audioPlayer?.stop()
                await AppServices.shared.authManager?.logout()
            }
            asking = false
        }
    }

    private func requestFromApple() async -> AgeGateAnswer {
        guard let presenter = Self.topViewController() else {
            // No window to present from; nothing is stored, so the
            // question comes back next launch.
            return .transientFailure
        }
        return await AgeGate.requestAnswer(presentingFrom: presenter)
    }

    /// The view controller Apple's sheet is presented over: the key
    /// window's topmost presented controller.
    private static func topViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// Terminal screen for someone Apple says is under 18. Nothing else in
/// the app is reachable while this is shown; deleting the app is what
/// clears it.
struct AgeGateRefusedView: View {
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 16

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 48)

                MMBrandWordmark(size: 34)
                    .padding(.horizontal, 16)

                Text("Only for Adults")
                    .font(MarqueeTheme.Fonts.h1)
                    .foregroundStyle(MarqueeTheme.Colors.text)
                    .multilineTextAlignment(.center)

                Text("Your Apple Account shows that you're under 18, so Household Disc Keeper isn't available to you.")
                    .font(.system(size: bodySize))
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                Text("You're welcome to delete the app and install it again once you turn 18.")
                    .font(.system(size: bodySize))
                    .foregroundStyle(MarqueeTheme.Colors.text2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .accessibilityIdentifier("age-gate-refused")

                Spacer(minLength: 12)
            }
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .mmGlowPage()
    }
}
