import Foundation
import Network
import Synchronization
import os.log

private let logger = Logger(subsystem: "net.stewart.mediamanager", category: "SsdpDiscovery")

actor SsdpDiscovery {
    static let serviceType = "urn:stewart:service:mediamanager:1"
    private static let multicastAddr = "239.255.255.250"
    private static let multicastPort: UInt16 = 1900

    func discover(timeout: TimeInterval = 3.0) async -> URL? {
        logger.info("discover: starting SSDP discovery, timeout=\(timeout)s, ST=\(Self.serviceType)")
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
                    if let url {
                        logger.info("discover: found server at \(url.absoluteString)")
                    } else {
                        logger.info("discover: no server found (timeout or error)")
                    }
                    continuation.resume(returning: url)
                }
            }

            // Use NWListener to receive the unicast response on a known port
            let listenerParams = NWParameters.udp
            listenerParams.allowLocalEndpointReuse = true

            guard let listener = try? NWListener(using: listenerParams, on: .any) else {
                logger.error("discover: failed to create NWListener")
                resumeOnce(nil)
                return
            }

            listener.newConnectionHandler = { connection in
                logger.info("discover: incoming connection from \(String(describing: connection.endpoint))")
                connection.start(queue: .global(qos: .userInitiated))
                connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { data, _, _, _ in
                    guard let data, let response = String(data: data, encoding: .utf8) else {
                        logger.warning("discover: received data but couldn't decode as UTF-8")
                        return
                    }
                    logger.info("discover: received \(data.count) bytes: \(response.prefix(200))")
                    // Only accept responses that match our service type
                    guard response.localizedCaseInsensitiveContains(Self.serviceType) else {
                        logger.info("discover: response doesn't match ST, ignoring")
                        return
                    }
                    if let url = Self.parseLocation(from: response) {
                        logger.info("discover: parsed LOCATION=\(url.absoluteString)")
                        listener.cancel()
                        resumeOnce(url)
                    } else {
                        logger.warning("discover: response matched ST but no LOCATION header found")
                    }
                }
            }

            listener.stateUpdateHandler = { state in
                logger.info("discover: listener state=\(String(describing: state))")
                if case .ready = state {
                    guard let listenerPort = listener.port else {
                        logger.error("discover: listener ready but no port assigned")
                        listener.cancel()
                        resumeOnce(nil)
                        return
                    }
                    logger.info("discover: listener ready on port \(listenerPort.rawValue)")

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
                        logger.info("discover: send connection state=\(String(describing: sendState))")
                        if case .ready = sendState {
                            let data = Data(mSearchMessage.utf8)
                            sendConnection.send(content: data, completion: .contentProcessed({ error in
                                if let error {
                                    logger.error("discover: M-SEARCH send failed: \(error.localizedDescription)")
                                } else {
                                    logger.info("discover: M-SEARCH sent (\(data.count) bytes) to \(Self.multicastAddr):\(Self.multicastPort)")
                                }
                            }))
                        } else if case .failed(let error) = sendState {
                            logger.error("discover: send connection failed: \(error.localizedDescription)")
                        }
                    }
                    sendConnection.start(queue: .global(qos: .userInitiated))
                } else if case .failed(let error) = state {
                    logger.error("discover: listener failed: \(error.localizedDescription)")
                    resumeOnce(nil)
                }
            }

            listener.start(queue: .global(qos: .userInitiated))

            // Timeout
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                logger.info("discover: timeout reached (\(timeout)s)")
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
