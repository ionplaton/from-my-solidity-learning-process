package belot.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class HostInfo(val address: String, val port: Int, val hostName: String, val players: Int, val started: Boolean)

object NetUtil {
    /** IPv4 addresses of this device, excluding loopback. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { result += it }
            }
        } catch (_: Exception) {
        }
        result += InetAddress.getByName("255.255.255.255")
        return result.distinct()
    }
}

/** Periodically announces a hosted game on the local network via UDP broadcast. */
class DiscoveryBeacon(private val server: GameServer, private val udpPort: Int = Protocol.UDP_PORT) {
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    while (running) {
                        val text = listOf(
                            Protocol.MAGIC, server.port, server.hostName.replace("|", " "),
                            server.playerCount, if (server.started) 1 else 0,
                        ).joinToString("|")
                        val data = text.toByteArray(Charsets.UTF_8)
                        for (addr in NetUtil.broadcastAddresses()) {
                            try {
                                socket.send(DatagramPacket(data, data.size, addr, udpPort))
                            } catch (_: Exception) {
                            }
                        }
                        Thread.sleep(1000)
                    }
                }
            } catch (_: Exception) {
            }
        }, "belot-beacon").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }
}

/** Listens for [DiscoveryBeacon] announcements. */
class DiscoveryListener(private val onHost: (HostInfo) -> Unit, private val udpPort: Int = Protocol.UDP_PORT) {
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        running = true
        Thread({
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.soTimeout = 1000
                s.bind(InetSocketAddress(udpPort))
                socket = s
                val buf = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split("|")
                    if (parts.size < 5 || parts[0] != Protocol.MAGIC) continue
                    val port = parts[1].toIntOrNull() ?: continue
                    onHost(
                        HostInfo(
                            address = packet.address.hostAddress ?: continue,
                            port = port,
                            hostName = parts[2],
                            players = parts[3].toIntOrNull() ?: 0,
                            started = parts[4] == "1",
                        )
                    )
                }
                s.close()
            } catch (_: Exception) {
            }
        }, "belot-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }
}
