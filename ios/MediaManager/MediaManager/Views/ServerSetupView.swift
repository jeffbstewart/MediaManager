import SwiftUI

struct ServerSetupView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var serverURL = ""
    @State private var isConnecting = false
    @State private var isSearching = true
    @State private var discoveredURL: URL?

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                Image(systemName: "film.stack")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)

                Text("Media Manager")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                if isSearching {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Searching for server on your network...")
                            .foregroundStyle(.secondary)
                    }
                } else if let discovered = discoveredURL {
                    VStack(spacing: 12) {
                        Label("Server found", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                            .font(.headline)
                        Text(discovered.absoluteString)
                            .foregroundStyle(.secondary)

                        Button(action: { connectTo(discovered.absoluteString) }) {
                            if isConnecting {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                            } else {
                                Text("Connect")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(isConnecting)
                        .padding(.horizontal)
                    }
                } else {
                    Text("Enter your server address to get started.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                if !isSearching {
                    VStack(spacing: 16) {
                        if discoveredURL != nil {
                            Text("Or enter a different address:")
                                .foregroundStyle(.secondary)
                                .font(.callout)
                        }

                        TextField("https://your-server.example.com", text: $serverURL)
                            .textFieldStyle(.roundedBorder)
                            .textContentType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onSubmit { connectTo(serverURL) }

                        if discoveredURL == nil {
                            Button(action: { connectTo(serverURL) }) {
                                if isConnecting {
                                    ProgressView()
                                        .frame(maxWidth: .infinity)
                                } else {
                                    Text("Connect")
                                        .frame(maxWidth: .infinity)
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .disabled(serverURL.isEmpty || isConnecting)
                        }
                    }
                    .padding(.horizontal)
                }

                if let error = authManager.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()
                Spacer()
            }
            .padding()
            .navigationTitle("")
            .task {
                await discoverServer()
            }
        }
    }

    private func discoverServer() async {
        isSearching = true
        let ssdp = SsdpDiscovery()
        discoveredURL = await ssdp.discover(timeout: 3.0)
        isSearching = false
    }

    private func connectTo(_ urlString: String) {
        isConnecting = true
        Task {
            await authManager.connectToServer(urlString: urlString)
            isConnecting = false
        }
    }
}
