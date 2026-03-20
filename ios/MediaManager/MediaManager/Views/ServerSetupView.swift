import SwiftUI

struct ServerSetupView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var serverURL = ""
    @State private var isConnecting = false

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

                Text("Enter your server address to get started.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                VStack(spacing: 16) {
                    TextField("https://your-server.example.com", text: $serverURL)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { connect() }

                    Button(action: connect) {
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
                .padding(.horizontal)

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
        }
    }

    private func connect() {
        isConnecting = true
        Task {
            await authManager.connectToServer(urlString: serverURL)
            isConnecting = false
        }
    }
}
