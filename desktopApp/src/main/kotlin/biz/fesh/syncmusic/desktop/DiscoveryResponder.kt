package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.ServerInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/** Answers UDP discovery broadcasts from phones in the same network. */
class DiscoveryResponder(private val info: () -> ServerInfo) {
    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "discovery") { loop() }
    }

    fun stop() {
        running = false
        socket?.close()
    }

    private fun loop() {
        while (running) {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                    socket = s
                    AppLog.i("Discovery listening on UDP ${Protocol.DISCOVERY_PORT}")
                    val buffer = ByteArray(1024)
                    while (running) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                        if (text != Protocol.DISCOVERY_MAGIC) continue
                        val reply = Protocol.json.encodeToString(ServerInfo.serializer(), info()).toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                }
            } catch (t: Throwable) {
                if (!running) return
                AppLog.w("Discovery socket error, retrying", t)
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}
