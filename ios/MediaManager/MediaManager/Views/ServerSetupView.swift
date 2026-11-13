import SwiftUI
import MediaManagerCore
import MediaManagerProtos

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

                MMBrandWordmark(size: 40)

                Text("Connect to your server")
                    .font(MarqueeTheme.Fonts.h2)
                    .foregroundStyle(MarqueeTheme.Colors.text)

                if let discovered = discoveredURL {
                    VStack(spacing: 12) {
                        Label("Server found", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(MarqueeTheme.Colors.green)
                            .font(MarqueeTheme.Fonts.control)
                        Text(discovered.absoluteString)
                            .font(MarqueeTheme.Fonts.meta)
                            .foregroundStyle(MarqueeTheme.Colors.text3)

                        Button(action: { connectTo(discovered.absoluteString) }) {
                            if isConnecting {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                            } else {
                                Text("Connect")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.mmPrimary)
                        .disabled(isConnecting)
                        .padding(.horizontal)
                    }
                }

                VStack(spacing: 16) {
                    if discoveredURL != nil {
                        Text("Or enter a different address:")
                            .foregroundStyle(MarqueeTheme.Colors.text2)
                            .font(MarqueeTheme.Fonts.body)
                    } else {
                        Text("Enter your server address to connect.")
                            .foregroundStyle(MarqueeTheme.Colors.text2)
                            .font(MarqueeTheme.Fonts.body)
                    }

                    TextField("https://your-server.example.com", text: $serverURL)
                        .textFieldStyle(.mmPill)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { connectTo(serverURL) }
                        .accessibilityIdentifier("server-url-field")

                    Button(action: { connectTo(serverURL) }) {
                        if isConnecting {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Connect")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(MMPillButtonStyle(prominent: discoveredURL == nil))
                    .disabled(serverURL.isEmpty || isConnecting)
                    .accessibilityIdentifier("server-connect")
                }
                .padding(.horizontal)

                if let error = authManager.error {
                    Text(error)
                        .foregroundStyle(MarqueeTheme.Colors.pink)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()

                Spacer()
            }
            .padding()
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .mmGlowPage()
            .navigationTitle("")
            .task {
                await discoverServer()
            }
        }
    }

    private func discoverServer() async {
        isSearching = true
        // Skip SSDP in snapshot mode — a discovered "Server found" banner
        // would render the real demo host in the captured PNG.
        if !SnapshotMode.isActive {
            let ssdp = SsdpDiscovery()
            discoveredURL = await ssdp.discover(timeout: 2.0)
        }
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
