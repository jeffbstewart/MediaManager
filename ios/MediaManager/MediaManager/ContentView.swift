import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationSplitView {
            List {
                Label("Home", systemImage: "house")
                Label("Catalog", systemImage: "film")
                Label("Search", systemImage: "magnifyingglass")
                Label("Wish List", systemImage: "heart")
            }
            .navigationTitle("Media Manager")
        } detail: {
            VStack(spacing: 16) {
                Image(systemName: "film.stack")
                    .font(.system(size: 64))
                    .foregroundStyle(.secondary)
                Text("Media Manager")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                Text("Connected to your collection.")
                    .foregroundStyle(.secondary)
            }
        }
    }
}

#Preview {
    ContentView()
}
