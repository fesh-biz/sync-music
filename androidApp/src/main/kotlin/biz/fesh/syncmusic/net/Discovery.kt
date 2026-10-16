package biz.fesh.syncmusic.net

import android.content.Context
import android.net.Network
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class FoundServer(val host: String, val info: ServerInfo)

/** UDP broadcast discovery of PCs running Sync Music. */
object Discovery {
    suspend fun discover(context: Context, network: Network?, timeoutMs: Long = 2_500): List<FoundServer> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, FoundServer>()
            val targets = WifiNet.broadcastAddresses(context, network)
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 250
                socket.bind(InetSocketAddress(0))
                try {
                    network?.bindSocket(socket)
                } catch (_: Throwable) {
                }
                val payload = Protocol.DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        for (target in targets) {
                            try {
                                socket.send(DatagramPacket(payload, payload.size, target, Protocol.DISCOVERY_PORT))
                            } catch (_: Throwable) {
                            }
                        }
                        nextSend = now + 700
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val info = Protocol.json.decodeFromString(ServerInfo.serializer(), text)
                        val host = packet.address?.hostAddress ?: continue
                        results[info.serverId] = FoundServer(host, info)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                socket.close()
            }
            results.values.toList()
        }
}
