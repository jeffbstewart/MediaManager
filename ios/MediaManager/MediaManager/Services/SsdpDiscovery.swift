import Foundation
import Network
import Synchronization

actor SsdpDiscovery {
    static let serviceType = "urn:stewart:service:mediamanager:1"
    private static let multicastAddr = "239.255.255.250"
    private static let multicastPort: UInt16 = 1900

    func discover(timeout: TimeInterval = 3.0) async -> URL? {
        let mSearchMessage = """
        M-SEARCH * HTTP/1.1\r
        HOST: \(Self.multicastAddr):\(Self.multicastPort)\r
        MAN: "ssdp:discover"\r
        MX: 2\r
        ST: \(Self.serviceType)\r
        \r

        """

        let result: URL? = await withCheckedContinuation { continuation in
            let resumed = Mutex(false)

            let resumeOnce: @Sendable (URL?) -> Void = { url in
                resumed.withLock { alreadyResumed in
                    guard !alreadyResumed else { return }
                    alreadyResumed = true
                    continuation.resume(returning: url)
                }
            }

            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            let group = NWEndpoint.hostPort(
                host: NWEndpoint.Host(Self.multicastAddr),
                port: NWEndpoint.Port(rawValue: Self.multicastPort)!
            )
            let connection = NWConnection(to: group, using: params)

            connection.stateUpdateHandler = { state in
                if case .ready = state {
                    let data = Data(mSearchMessage.utf8)
                    connection.send(content: data, completion: .contentProcessed({ _ in }))

                    connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { data, _, _, _ in
                        guard let data, let response = String(data: data, encoding: .utf8) else {
                            return
                        }
                        if let url = Self.parseLocation(from: response) {
                            connection.cancel()
                            resumeOnce(url)
                        }
                    }
                }
            }

            connection.start(queue: .global(qos: .userInitiated))

            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                connection.cancel()
                resumeOnce(nil)
            }
        }
        return result
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
