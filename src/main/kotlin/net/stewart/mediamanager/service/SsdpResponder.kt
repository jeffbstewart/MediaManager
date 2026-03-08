package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.net.*

/**
 * SSDP responder that advertises the mediaManager server on the local network.
 *
 * Listens for M-SEARCH requests on the SSDP multicast group (239.255.255.250:1900)
 * and responds with the server's base URL when the search target matches
 * "urn:stewart:service:mediamanager:1".
 *
 * Roku devices (and other clients) can discover the server automatically
 * without manual URL entry.
 */
class SsdpResponder(private val serverPort: Int) : Thread("ssdp-responder") {

    private val log = LoggerFactory.getLogger(SsdpResponder::class.java)

    companion object {
        private const val MULTICAST_ADDR = "239.255.255.250"
        private const val MULTICAST_PORT = 1900
        const val SERVICE_TYPE = "urn:stewart:service:mediamanager:1"
    }

    @Volatile
    private var running = true
    private var socket: MulticastSocket? = null

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            log.info("SSDP: binding to multicast {}:{} (serverPort={})", MULTICAST_ADDR, MULTICAST_PORT, serverPort)
            val sock = MulticastSocket(MULTICAST_PORT)
            sock.reuseAddress = true

            // Find the best non-Docker, non-loopback interface with an IPv4 address
            val allIfaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback } ?: emptyList()
            allIfaces.forEach { iface ->
                val addrs = iface.inetAddresses.toList().map { it.hostAddress }
                log.info("SSDP: network interface {} — addresses: {}", iface.displayName, addrs)
            }

            val lanIface = allIfaces.firstOrNull { iface ->
                !iface.name.startsWith("docker") && !iface.name.startsWith("veth") &&
                    !iface.name.startsWith("br-") &&
                    iface.inetAddresses.toList().any { it is Inet4Address }
            }

            val joinIface: NetworkInterface?
            if (lanIface != null) {
                log.info("SSDP: joining multicast on LAN interface {} ({})", lanIface.displayName, lanIface.name)
                sock.joinGroup(InetSocketAddress(group, MULTICAST_PORT), lanIface)
                joinIface = lanIface
            } else {
                log.warn("SSDP: no suitable LAN interface found, joining on default (may not work in Docker)")
                sock.joinGroup(InetSocketAddress(group, MULTICAST_PORT), null)
                joinIface = null
            }

            sock.soTimeout = 2000 // check running flag every 2s
            socket = sock

            log.info("SSDP responder started, listening on {}:{}", MULTICAST_ADDR, MULTICAST_PORT)

            val buf = ByteArray(1024)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                    handlePacket(packet, sock)
                } catch (_: SocketTimeoutException) {
                    // Normal — just loop and check running flag
                }
            }

            sock.leaveGroup(InetSocketAddress(group, MULTICAST_PORT), joinIface)
            sock.close()
            log.info("SSDP responder stopped")
        } catch (e: BindException) {
            log.error("SSDP responder FATAL: cannot bind to port {} — another process (e.g. Synology UPnP) is using it. " +
                "Disable UPnP in DSM or free the port.", MULTICAST_PORT)
            System.exit(1)
        } catch (e: Exception) {
            if (running) {
                log.error("SSDP responder FATAL: {}", e.message)
                System.exit(1)
            }
        }
    }

    fun shutdown() {
        running = false
        socket?.close()
    }

    private fun handlePacket(packet: DatagramPacket, sock: MulticastSocket) {
        val message = String(packet.data, 0, packet.length)
        val senderAddr = packet.address
        val senderPort = packet.port

        if (!message.startsWith("M-SEARCH", ignoreCase = true)) return
        if (!message.contains(SERVICE_TYPE, ignoreCase = true)) return

        log.info("SSDP: received matching M-SEARCH from {}:{}", senderAddr, senderPort)

        // Determine our best local IP that can reach the sender
        val localIp = getReachableLocalIp(senderAddr)
        if (localIp == null) {
            log.warn("SSDP: could not determine local IP reachable from {} — cannot respond", senderAddr)
            return
        }
        val location = "http://$localIp:$serverPort"

        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("ST: $SERVICE_TYPE\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: mediaManager/1.0\r\n")
            append("USN: uuid:mediamanager::$SERVICE_TYPE\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("\r\n")
        }

        val responseBytes = response.toByteArray()
        val responsePacket = DatagramPacket(responseBytes, responseBytes.size, senderAddr, senderPort)

        // Send response via a fresh unicast socket bound to the LAN IP — ensures the
        // packet exits via the correct NIC (not a Docker bridge) and avoids multicast
        // socket reply issues with some devices (Roku)
        try {
            DatagramSocket(InetSocketAddress(localIp, 0)).use { unicast ->
                unicast.send(responsePacket)
                log.info("SSDP: responded to {}:{} with LOCATION: {} (via unicast {}:{})",
                    senderAddr, senderPort, location, localIp, unicast.localPort)
            }
        } catch (e: Exception) {
            log.warn("SSDP: failed to send unicast response to {}:{} — {}", senderAddr, senderPort, e.message)
        }
    }

    private fun getReachableLocalIp(target: InetAddress): String? {
        return try {
            DatagramSocket().use { tempSock ->
                tempSock.connect(target, MULTICAST_PORT)
                val ip = tempSock.localAddress.hostAddress
                log.info("SSDP: local IP for reaching {} resolved to {}", target, ip)
                ip
            }
        } catch (e: Exception) {
            log.warn("SSDP: failed to resolve local IP for {}: {}", target, e.message)
            null
        }
    }
}
