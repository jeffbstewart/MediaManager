import SwiftUI

struct RootView: View {
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        switch authManager.state {
        case .needsServer:
            ServerSetupView()
        case .needsLogin(let serverURL):
            LoginView(serverURL: serverURL)
        case .authenticated:
            ContentView()
        }
    }
}
