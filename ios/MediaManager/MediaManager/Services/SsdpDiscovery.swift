import Foundation
import Network
import Synchronization

actor SsdpDiscovery {
    static let serviceType = "urn:stewart:service:mediamanager:1"
    private static let multicastAddr = "239.255.255.250"
    private static let multicastPort: UInt16 = 1900

    func discover(timeout: TimeInterval = 3.0) async -> URL? {
        let mSearchMessage =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: \(Self.multicastAddr):\(Self.multicastPort)\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: \(Self.serviceType)\r\n" +
            "\r\n"

        return await withCheckedContinuation { continuation in
            let resumed = Mutex(false)

            let resumeOnce: @Sendable (URL?) -> Void = { url in
                resumed.withLock { alreadyResumed in
                    guard !alreadyResumed else { return }
                    alreadyResumed = true
                    continuation.resume(returning: url)
                }
            }

            // Use NWListener to receive the unicast response on a known port
            let listenerParams = NWParameters.udp
            listenerParams.allowLocalEndpointReuse = true

            guard let listener = try? NWListener(using: listenerParams, on: .any) else {
                resumeOnce(nil)
                return
            }

            listener.newConnectionHandler = { connection in
                connection.start(queue: .global(qos: .userInitiated))
                connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { data, _, _, _ in
                    guard let data, let response = String(data: data, encoding: .utf8) else { return }
                    if let url = Self.parseLocation(from: response) {
                        listener.cancel()
                        resumeOnce(url)
                    }
                }
            }

            listener.stateUpdateHandler = { state in
                if case .ready = state {
                    guard let listenerPort = listener.port else {
                        listener.cancel()
                        resumeOnce(nil)
                        return
                    }

                    // Send M-SEARCH from a UDP connection bound to our listener port
                    let sendParams = NWParameters.udp
                    sendParams.requiredLocalEndpoint = NWEndpoint.hostPort(
                        host: "0.0.0.0", port: listenerPort)
                    sendParams.allowLocalEndpointReuse = true

                    let target = NWEndpoint.hostPort(
                        host: NWEndpoint.Host(Self.multicastAddr),
                        port: NWEndpoint.Port(rawValue: Self.multicastPort)!
                    )
                    let sendConnection = NWConnection(to: target, using: sendParams)
                    sendConnection.stateUpdateHandler = { sendState in
                        if case .ready = sendState {
                            let data = Data(mSearchMessage.utf8)
                            sendConnection.send(content: data, completion: .contentProcessed({ _ in
                                // Keep sendConnection alive until timeout
                            }))
                        }
                    }
                    sendConnection.start(queue: .global(qos: .userInitiated))
                }
            }

            listener.start(queue: .global(qos: .userInitiated))

            // Timeout
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                listener.cancel()
                resumeOnce(nil)
            }
        }
    }

    private static func parseLocation(from response: String) -> URL? {
        for line in response.components(separatedBy: "\r\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.uppercased().hasPrefix("LOCATION:") {
                let value = trimmed.dropFirst("LOCATION:".count)
                    .trimmingCharacters(in: .whitespaces)
                return URL(string: value)
            }
        }
        return nil
    }
}
