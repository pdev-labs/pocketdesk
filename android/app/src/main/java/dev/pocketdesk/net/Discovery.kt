package dev.pocketdesk.net

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Finds PocketDesk hosts on the local network.
 *
 * The host broadcasts a beacon on UDP 8766 and answers a discover probe, so the
 * list appears immediately instead of after the next three-second beacon. The
 * address used is the packet's source address: whatever interface the beacon
 * arrived on is by definition reachable from the phone.
 */
class Discovery(
    private val onUpdate: (List<Host>) -> Unit,
) {

    data class Host(
        val name: String,
        val address: String,
        val port: Int,
        val version: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val hosts = linkedMapOf<String, Host>()
    private val seenAt = HashMap<String, Long>()

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true
        hosts.clear()
        seenAt.clear()
        publish()

        thread = Thread({ loop() }, "pocketdesk-discovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        try {
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1_000
                broadcast = true
            }
            socket = udp

            val probe = """{"app":"pocketdesk","t":"discover"}""".toByteArray()
            var lastProbe = 0L

            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastProbe > 2_500) {
                    lastProbe = now
                    runCatching {
                        udp.send(
                            DatagramPacket(
                                probe,
                                probe.size,
                                InetAddress.getByName("255.255.255.255"),
                                PORT,
                            ),
                        )
                    }
                }

                val buffer = ByteArray(2_048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    prune(now)
                    continue
                } catch (_: Exception) {
                    if (!running) break
                    continue
                }

                parse(packet, now)
                prune(now)
            }
        } catch (_: Exception) {
            if (running) publish()
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun parse(packet: DatagramPacket, now: Long) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optString("app") != "pocketdesk") return

        val address = packet.address?.hostAddress ?: return
        val port = json.optInt("port", 0).takeIf { it in 1..65535 } ?: return
        val host = Host(
            name = json.optString("name", "PocketDesk"),
            address = address,
            port = port,
            version = json.optString("version"),
        )

        val key = "$address:$port"
        seenAt[key] = now
        if (hosts.put(key, host) != host) {
            publish()
        }
    }

    private fun prune(now: Long) {
        val stale = seenAt.filterValues { now - it > 9_000 }.keys
        if (stale.isEmpty()) return
        var changed = false
        for (key in stale) {
            seenAt.remove(key)
            hosts.remove(key)
            changed = true
        }
        if (changed) publish()
    }

    private fun publish() {
        val snapshot = hosts.values.toList()
        main.post { onUpdate(snapshot) }
    }

    companion object {
        const val PORT = 8766
    }
}
