package com.alec.hearthtv.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

data class SsdpDevice(
    val location: String,
    val host: String,
    val st: String,
    val usn: String,
    val server: String?,
    val respondedFrom: String,
)

/**
 * SSDP M-SEARCH, the discovery every one of our devices answers: the Bravia (ScalarWebAPI), the Sonos
 * players (ZonePlayer) and the Roku (roku:ecp). [target] is the multicast group by default; tests point it
 * at a loopback responder. Pass a [socketProvider] that binds to the Wi-Fi network on the phone.
 */
class SsdpDiscovery(
    private val socketProvider: () -> DatagramSocket = { DatagramSocket() },
    private val target: InetSocketAddress = InetSocketAddress("239.255.255.250", 1900),
) {
    companion object {
        const val ST_SONY_SCALAR = "urn:schemas-sony-com:service:ScalarWebAPI:1"
        const val ST_SONOS = "urn:schemas-upnp-org:device:ZonePlayer:1"
        const val ST_ROKU = "roku:ecp"
        const val ST_ALL = "ssdp:all"
    }

    suspend fun search(st: String, timeoutMs: Int = 2500, mx: Int = 2): List<SsdpDevice> = withContext(Dispatchers.IO) {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\n\r\n"
        val bytes = msg.toByteArray(Charsets.US_ASCII)
        socketProvider().use { sock ->
            sock.soTimeout = 250
            repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, target)) }
            val found = LinkedHashMap<String, SsdpDevice>()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                parse(text, p.address.hostAddress)?.let { d -> found.putIfAbsent(d.usn.ifBlank { d.location }, d) }
            }
            found.values.toList()
        }
    }

    /** Parses one SSDP response; `null` for anything that is not a 200 with a LOCATION. */
    fun parse(text: String, from: String): SsdpDevice? {
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().startsWith("HTTP/1.1 200")) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            headers.putIfAbsent(line.substring(0, i).trim().uppercase(), line.substring(i + 1).trim())
        }
        val location = headers["LOCATION"] ?: return null
        val host = runCatching { URI(location).host }.getOrNull() ?: from
        return SsdpDevice(location, host, headers["ST"] ?: "", headers["USN"] ?: "", headers["SERVER"], from)
    }
}
