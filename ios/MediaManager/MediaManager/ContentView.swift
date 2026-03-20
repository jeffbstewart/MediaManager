import SwiftUI

enum Tab: Hashable {
    case home, catalog, search, wishList
}

struct ContentView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var selectedTab: Tab? = .home

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedTab) {
                Label("Home", systemImage: "house")
                    .tag(Tab.home)
                Label("Catalog", systemImage: "film")
                    .tag(Tab.catalog)
                Label("Search", systemImage: "magnifyingglass")
                    .tag(Tab.search)
                Label("Wish List", systemImage: "heart")
                    .tag(Tab.wishList)
            }
            .navigationTitle("Media Manager")
            .toolbar {
                ToolbarItem(placement: .bottomBar) {
                    Button("Sign Out") {
                        Task { await authManager.logout() }
                    }
                    .font(.callout)
                }
            }
        } detail: {
            if let selectedTab {
                switch selectedTab {
                case .home:
                    HomeView()
                case .catalog:
                    PlaceholderView(title: "Catalog", icon: "film")
                case .search:
                    PlaceholderView(title: "Search", icon: "magnifyingglass")
                case .wishList:
                    PlaceholderView(title: "Wish List", icon: "heart")
                }
            } else {
                HomeView()
            }
        }
    }
}

struct HomeView: View {
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 64))
                .foregroundStyle(.green)
            Text("Connected")
                .font(.largeTitle)
                .fontWeight(.bold)
            if let info = authManager.serverInfo {
                Text("Server v\(info.version) — \(info.titleCount) titles")
                    .foregroundStyle(.secondary)
                Text("Capabilities: \(info.capabilities.joined(separator: ", "))")
                    .foregroundStyle(.secondary)
                    .font(.callout)
            }
        }
        .padding()
    }
}

struct PlaceholderView: View {
    let title: String
    let icon: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 64))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.largeTitle)
                .fontWeight(.bold)
            Text("Coming soon")
                .foregroundStyle(.secondary)
        }
    }
}

#Preview {
    ContentView()
        .environment(AuthManager())
}
